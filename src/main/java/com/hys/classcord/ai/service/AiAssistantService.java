package com.hys.classcord.ai.service;

import com.hys.classcord.ai.entity.AiMessage;
import com.hys.classcord.ai.entity.AiSession;
import com.hys.classcord.ai.enums.AiMessageRole;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.AiSessionRepository;
import com.hys.classcord.ai.strategy.RagIndexingStrategy;
import com.hys.classcord.ai.strategy.RagStrategyFactory;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private final MaterialRepository materialRepository;
    private final RabbitTemplate rabbitTemplate;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    private final UserRepository userRepository;
    private final AiSessionRepository aiSessionRepository;
    private final AiMessageRepository aiMessageRepository;
    private final RagStrategyFactory strategyFactory;
    private final TransactionTemplate transactionTemplate;

    @Value("classpath:prompts/rag-prompt.st")
    private Resource promptResource;

    @Transactional
    public void enableAiAssistant(UUID materialId) {
        // 使用 FOR UPDATE 鎖定讀取，確保並發安全
        Material material =
                materialRepository
                        .findByIdForUpdate(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        // 防禦性狀態判斷 (因為有鎖，此時狀態一定是最新且安全的)
        if (material.getStatus() == MaterialStatus.PROCESSING) {
            throw new MaterialException(MaterialErrorCode.AI_ASSISTANT_PROCESSING);
        }
        if (material.getStatus() == MaterialStatus.ENABLED) {
            throw new MaterialException(MaterialErrorCode.AI_ASSISTANT_ALREADY_ENABLED);
        }

        // 1. 狀態
        material.markAsProcessing();
        materialRepository.save(material);

        // 2. 只有在資料庫交易成功 Commit 後才發送 MQ 消息，防止 Consumer 併發讀到未提交狀態
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            rabbitTemplate.convertAndSend(
                                    RabbitMQConfig.AI_EXCHANGE,
                                    RabbitMQConfig.ROUTING_KEY_RAG_PROCESS,
                                    materialId.toString());
                            log.info("【事務提交後】已成功推送 RAG 處理消息至 RabbitMQ: materialId={}", materialId);
                        }
                    });
        } else {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AI_EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY_RAG_PROCESS,
                    materialId.toString());
            log.info("【無事務環境】已直接推送 RAG 處理消息至 RabbitMQ: materialId={}", materialId);
        }
    }

    /** 1. 創建新的對話會話 */
    @Transactional
    public AiSession createSession(UUID userId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        if (material.getStatus() != MaterialStatus.ENABLED) {
            throw new MaterialException(
                    MaterialErrorCode.AI_ASSISTANT_PROCESSING, "該教材尚未完成 AI 助教啟用，無法建立對話");
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new MaterialException(MaterialErrorCode.USER_NOT_FOUND));

        AiSession session = AiSession.builder().user(user).material(material).build();

        return aiSessionRepository.save(session);
    }

    /** 2. 查詢使用者在某教材下的對話會話列表 */
    @Transactional(readOnly = true)
    public List<AiSession> listSessions(UUID userId, UUID materialId) {
        return aiSessionRepository.findByUserIdAndMaterialIdOrderByCreatedAtDesc(
                userId, materialId);
    }

    /** 3. 獲取會話中最近 10 條歷史訊息 */
    @Transactional(readOnly = true)
    public List<AiMessage> getSessionMessages(UUID userId, UUID sessionId) {
        AiSession session =
                aiSessionRepository
                        .findById(sessionId)
                        .orElseThrow(
                                () ->
                                        new MaterialException(
                                                MaterialErrorCode.MATERIAL_NOT_FOUND, "對話會話不存在"));

        if (!session.getUser().getId().equals(userId)) {
            throw new MaterialException(MaterialErrorCode.INSUFFICIENT_PERMISSIONS, "您無權查看此對話紀錄");
        }

        // 預設撈取最近 10 條訊息
        List<AiMessage> messages =
                aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, Limit.of(10));

        // 原本是新到舊，反轉為舊到新 (時間遞增順序)
        List<AiMessage> chronologicalMessages = new ArrayList<>(messages);
        Collections.reverse(chronologicalMessages);
        return chronologicalMessages;
    }

    // todo 問題意圖解析
    /** 4. 在會話中進行連續對話 (載入歷史上下文 + RAG) */
    public String chatInSession(UUID userId, UUID sessionId, String userMessage) {
        ChatContext ctx = prepareChatContext(userId, sessionId, userMessage);

        // F. 呼叫 Spring AI 執行檢索與問答 (傳入歷史，並指定當前提問為 .user() 以替換 {query})
        String assistantReply =
                chatClient
                        .prompt()
                        .messages(ctx.springAiMessages())
                        .user(u -> u.text(userMessage).param("query", userMessage))
                        .advisors(
                                new QuestionAnswerAdvisor(
                                        vectorStore,
                                        SearchRequest.builder()
                                                .query(userMessage) // 僅使用當前提問內容進行向量檢索，確保檢索精準度
                                                .filterExpression(ctx.filterExpression())
                                                .build(),
                                        ctx.promptTemplate()))
                        .call()
                        .content();

        // G. 儲存 AI 助教的回覆訊息至資料庫
        AiMessage assistantAiMsg =
                AiMessage.builder()
                        .session(ctx.session())
                        .role(AiMessageRole.ASSISTANT)
                        .content(assistantReply)
                        .build();
        aiMessageRepository.save(assistantAiMsg);

        return assistantReply;
    }

    /** 5. 在會話中進行流式連續對話 (載入歷史上下文 + RAG + SSE 吐字) */
    public Flux<String> chatInSessionStream(UUID userId, UUID sessionId, String userMessage) {
        ChatContext ctx = prepareChatContext(userId, sessionId, userMessage);

        StringBuilder fullReply = new StringBuilder();

        return chatClient
                .prompt()
                .messages(ctx.springAiMessages())
                .user(u -> u.text(userMessage).param("query", userMessage))
                .advisors(
                        new QuestionAnswerAdvisor(
                                vectorStore,
                                SearchRequest.builder()
                                        .query(userMessage)
                                        .filterExpression(ctx.filterExpression())
                                        .build(),
                                ctx.promptTemplate()))
                .stream()
                .content()
                .doOnNext(fullReply::append)
                .doOnComplete(
                        () ->
                                Mono.fromRunnable(
                                                () ->
                                                        saveAssistantMessage(
                                                                sessionId, fullReply.toString()))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe());
    }

    /** 建立一個內部的共用 ChatContext，用來封裝 RAG 與歷史訊息前置載入邏輯 */
    private ChatContext prepareChatContext(UUID userId, UUID sessionId, String userMessage) {
        AiSession session =
                aiSessionRepository
                        .findWithUserAndMaterialById(sessionId)
                        .orElseThrow(
                                () ->
                                        new MaterialException(
                                                MaterialErrorCode.MATERIAL_NOT_FOUND, "對話會話不存在"));

        if (!session.getUser().getId().equals(userId)) {
            throw new MaterialException(MaterialErrorCode.INSUFFICIENT_PERMISSIONS, "您無權在此對話中發言");
        }

        Material material = session.getMaterial();
        if (material.getStatus() != MaterialStatus.ENABLED) {
            throw new MaterialException(
                    MaterialErrorCode.AI_ASSISTANT_PROCESSING, "該教材尚未完成 AI 助教啟用，暫無法回答");
        }

        // A. 效能與記憶體優化：先撈取前 9 條歷史訊息 (新到舊)
        List<AiMessage> historyEntities =
                aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, Limit.of(9));
        List<AiMessage> chronologicalHistory = new ArrayList<>(historyEntities);
        Collections.reverse(chronologicalHistory);

        // B. 儲存當前使用者的提問訊息至資料庫
        AiMessage userAiMsg =
                AiMessage.builder()
                        .session(session)
                        .role(AiMessageRole.USER)
                        .content(userMessage)
                        .build();
        aiMessageRepository.save(userAiMsg);

        // C. 組裝為 Spring AI 支援的 Message 清單 (僅歷史紀錄)
        List<Message> springAiMessages = new ArrayList<>();
        for (AiMessage msg : chronologicalHistory) {
            if (AiMessageRole.USER == msg.getRole()) {
                springAiMessages.add(new UserMessage(msg.getContent()));
            } else if (AiMessageRole.ASSISTANT == msg.getRole()) {
                springAiMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        // D. 建立多租戶向量過濾條件 (限定在該教材下)
        var filterExpression =
                new FilterExpressionBuilder()
                        .eq("material_id", material.getId().toString())
                        .build();

        // E. 讀取外部 ST 提示詞資源
        String promptTemplate;
        try {
            promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("無法加載 AI 提示詞模板檔案", e);
            throw new IllegalStateException("AI 服務內部錯誤，無法載入模板", e);
        }

        return new ChatContext(session, springAiMessages, filterExpression, promptTemplate);
    }

    /** 將流式對話最終的完整回覆寫入資料庫 (使用 transactionTemplate 確保非同步線程中事務正常執行，並排除 self-invocation 警告) */
    public void saveAssistantMessage(UUID sessionId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(
                status -> {
                    AiSession session = aiSessionRepository.findById(sessionId).orElse(null);
                    if (session != null) {
                        AiMessage assistantAiMsg =
                                AiMessage.builder()
                                        .session(session)
                                        .role(AiMessageRole.ASSISTANT)
                                        .content(reply)
                                        .build();
                        aiMessageRepository.save(assistantAiMsg);
                        log.info("RAG 串流對話完整回覆已異步存檔成功: sessionId={}", sessionId);
                    }
                });
    }

    /**
     * 執行教材 RAG 切片索引與狀態啟用。 本方法不帶 @Transactional，以確保慢速的 Tika 解析與 Gemini Embedding 外部 API 調用在交易外部執行，
     * 防止長時間佔用資料庫連線池與行鎖。
     */
    public void indexMaterialAndEnable(UUID materialId, byte[] fileBytes) {
        // 1. 在極短交易中查詢教材及相關聯的 Message -> Channel -> Server 實體 (預防交易外 LazyInitializationException)
        Material material =
                transactionTemplate.execute(
                        status ->
                                materialRepository
                                        .findWithAssociationsById(materialId)
                                        .orElseThrow(
                                                () ->
                                                        new MaterialException(
                                                                MaterialErrorCode
                                                                        .MATERIAL_NOT_FOUND)));

        // 2. 在交易外執行慢速的 RAG 解析與向量化 (包含慢速 CPU 運算與 Gemini API 遠端網路請求)
        RagIndexingStrategy strategy = strategyFactory.getStrategy();
        strategy.processAndIndex(material, fileBytes);

        // 3. 在極短交易中利用 Pessimistic Lock (FOR UPDATE) 鎖定教材並更新狀態為 ENABLED
        transactionTemplate.executeWithoutResult(
                status -> {
                    Material dbMaterial =
                            materialRepository
                                    .findByIdForUpdate(materialId)
                                    .orElseThrow(
                                            () ->
                                                    new MaterialException(
                                                            MaterialErrorCode.MATERIAL_NOT_FOUND));
                    dbMaterial.markAsEnabled();
                    materialRepository.save(dbMaterial);
                });
    }

    /** 在資料庫交易中將教材標記為啟用失敗。 */
    @Transactional
    public void markMaterialAsFailed(UUID materialId, String errorMessage) {
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material != null) {
            material.markAsFailed(errorMessage);
            materialRepository.save(material);
        }
    }

    private record ChatContext(
            AiSession session,
            List<Message> springAiMessages,
            Expression filterExpression,
            String promptTemplate) {}
}
