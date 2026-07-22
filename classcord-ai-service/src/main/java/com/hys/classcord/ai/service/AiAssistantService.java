package com.hys.classcord.ai.service;

import com.hys.classcord.ai.client.MaterialClient;
import com.hys.classcord.ai.config.AiLimitProperties;
import com.hys.classcord.ai.dto.AiLimitStatusResponse;
import com.hys.classcord.ai.entity.AiMessage;
import com.hys.classcord.ai.entity.AiSession;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.enums.AiMessageRole;
import com.hys.classcord.ai.exception.AiException;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.AiSessionRepository;
import com.hys.classcord.ai.strategy.RagIndexingStrategy;
import com.hys.classcord.ai.strategy.RagStrategyFactory;
import com.hys.classcord.common.dto.InternalMaterialDto;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.enums.MaterialStatus;
import io.seata.spring.annotation.GlobalTransactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private final MaterialClient materialClient;
    private final RabbitTemplate rabbitTemplate;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    private final AiSessionRepository aiSessionRepository;
    private final AiMessageRepository aiMessageRepository;
    private final RagStrategyFactory strategyFactory;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redisTemplate;
    private final AiLimitProperties aiLimitProperties;

    private static final ZoneId ZONE_TAIPEI = ZoneId.of("Asia/Taipei");

    @Value("classpath:prompts/rag-prompt.st")
    private Resource promptResource;

    @GlobalTransactional(name = "enable-ai-assistant", rollbackFor = Exception.class)
    public void enableAiAssistant(UUID materialId) {
        // 1. 透過 OpenFeign 遠程呼叫 main-service 進行悲觀鎖驗證與狀態更新
        materialClient.markAsProcessing(materialId);

        // 2. 發送 MQ 消息至 RabbitMQ 進行背景 RAG 向量化
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.AI_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_RAG_PROCESS,
                materialId.toString());
        log.info("已成功標記處理中並推送 RAG 處理消息至 RabbitMQ: materialId={}", materialId);
    }

    /** 1. 創建新的對話會話 */
    @Transactional
    public AiSession createSession(UUID userId, UUID materialId) {
        InternalMaterialDto material = materialClient.getMaterial(materialId);

        if (material.status() != MaterialStatus.ENABLED) {
            throw new AiException(AiErrorCode.AI_ASSISTANT_PROCESSING, "該教材尚未完成 AI 助教啟用，無法建立對話");
        }

        AiSession session = AiSession.builder().userId(userId).materialId(materialId).build();

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
                        .orElseThrow(() -> new AiException(AiErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(userId)) {
            throw new AiException(AiErrorCode.SESSION_ACCESS_DENIED);
        }

        // 預設撈取最近 10 條訊息
        List<AiMessage> messages =
                aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, Limit.of(10));

        // 原本是新到舊，反轉為舊到新 (時間遞增順序)
        List<AiMessage> chronologicalMessages = new ArrayList<>(messages);
        Collections.reverse(chronologicalMessages);
        return chronologicalMessages;
    }

    // todo 問題意圖解析 Function Calling
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
                        () -> {
                            // 非常重要！！保證線程安全
                            String finalReply = fullReply.toString();
                            Mono.fromRunnable(() -> saveAssistantMessage(sessionId, finalReply))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnError(
                                            error ->
                                                    log.error(
                                                            "【嚴重警告】AI 對話存檔失敗！會話 ID: {}, 錯誤原因: {}",
                                                            sessionId,
                                                            error.getMessage(),
                                                            error))
                                    .subscribe();
                        });
    }

    /** 建立一個內部的共用 ChatContext，用來封裝 RAG 與歷史訊息前置載入邏輯 */
    private ChatContext prepareChatContext(UUID userId, UUID sessionId, String userMessage) {
        AiSession session =
                aiSessionRepository
                        .findById(sessionId)
                        .orElseThrow(() -> new AiException(AiErrorCode.SESSION_NOT_FOUND));

        if (!session.getUserId().equals(userId)) {
            throw new AiException(AiErrorCode.SESSION_ACCESS_DENIED, "您無權在此對話中發言");
        }

        com.hys.classcord.common.dto.InternalMaterialDto material =
                materialClient.getMaterial(session.getMaterialId());
        if (material.status() != MaterialStatus.ENABLED) {
            throw new AiException(AiErrorCode.AI_ASSISTANT_PROCESSING, "該教材尚未完成 AI 助教啟用，暫無法回答");
        }

        // A. 效能與記憶體優化：先撈取前 9 條歷史訊息 (新到舊)
        List<AiMessage> historyEntities =
                aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, Limit.of(9));
        List<AiMessage> chronologicalHistory = new ArrayList<>(historyEntities);
        Collections.reverse(chronologicalHistory);

        // B. 儲存當前使用者的提問訊息至資料庫
        // todo 決定是否要馬上存資料庫（應對使用者中離情境）
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
                        .eq("material_id", session.getMaterialId().toString())
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
        // 1. 透過 Feign 獲取教材 DTO 元數據
        InternalMaterialDto material = materialClient.getMaterial(materialId);

        // 2. 執行慢速的 RAG 解析與向量化 (包含 Tika 切片與 Gemini Embedding 向量寫庫)
        RagIndexingStrategy strategy = strategyFactory.getStrategy();
        strategy.processAndIndex(material, fileBytes);

        // 3. 向量化成功，透過 Feign 通知 main-service 將狀態更新為 ENABLED 並廣播通知前端
        materialClient.markAsEnabled(materialId);
        log.info("【AI 微服務】教材 RAG 向量化完成並已通知 main-service 啟用: materialId={}", materialId);
    }

    /** 在資料庫交易中將教材標記為啟用失敗。 */
    public void markMaterialAsFailed(UUID materialId, String errorMessage) {
        try {
            materialClient.markAsFailed(
                    materialId, new com.hys.classcord.common.dto.FailMaterialRequest(errorMessage));
            log.info("【AI 微服務】已通知 main-service 標記教材 {} 處理失敗: {}", materialId, errorMessage);
        } catch (Exception e) {
            log.error("通知 main-service 標記教材失敗時發生異常: ", e);
        }
    }

    /** 獲取今日全站 AI 的調用額度狀態與估算費用 */
    public AiLimitStatusResponse getAiLimitStatus() {
        String dateStr = LocalDate.now(ZONE_TAIPEI).toString();
        String chatKey = "ai:all-site:limit:" + dateStr;
        String embedKey = "ai:all-site:embedding-limit:" + dateStr;

        String chatVal = redisTemplate.opsForValue().get(chatKey);
        String embedVal = redisTemplate.opsForValue().get(embedKey);

        long chatCount = chatVal == null ? 0L : Long.parseLong(chatVal);
        long embedCount = embedVal == null ? 0L : Long.parseLong(embedVal);

        int chatDailyMax = aiLimitProperties.getChatDailyMax();
        int embeddingDailyMax = aiLimitProperties.getEmbeddingDailyMax();

        // 確保超出的無效請求次數不會計入實際的計數與成本計算中
        long displayChatCount = Math.min(chatCount, chatDailyMax);
        long displayEmbedCount = Math.min(embedCount, embeddingDailyMax);

        // 估算成本計算 (Gemini 2.5 Flash / gemini-embedding-001)
        // Chat: 輸入單價 $0.30/M, 輸出單價 $2.50/M.
        // 假設平均對話耗用：輸入 3,000 tokens ($0.0009), 輸出 300 tokens ($0.00075)，合計單次約 $0.00165 USD
        // Embedding: 輸入單價 $0.15/M.
        // 假設平均切片耗用：輸入 800 tokens，單次約 $0.00012 USD
        BigDecimal chatCostPerCall = new BigDecimal("0.00165");
        BigDecimal embedCostPerCall = new BigDecimal("0.00012");
        BigDecimal exchangeRate = new BigDecimal("32.5");

        BigDecimal chatCost = BigDecimal.valueOf(displayChatCount).multiply(chatCostPerCall);
        BigDecimal embedCost = BigDecimal.valueOf(displayEmbedCount).multiply(embedCostPerCall);
        BigDecimal totalCostUsd = chatCost.add(embedCost);
        BigDecimal totalCostTwd = totalCostUsd.multiply(exchangeRate);

        // 四捨五入處理 (USD 4位小數，TWD 2位小數)
        double finalCostUsd = totalCostUsd.setScale(4, RoundingMode.HALF_UP).doubleValue();
        double finalCostTwd = totalCostTwd.setScale(2, RoundingMode.HALF_UP).doubleValue();

        double chatProgress = chatDailyMax > 0 ? ((double) chatCount / chatDailyMax) * 100 : 0.0;
        double embedProgress =
                embeddingDailyMax > 0 ? ((double) embedCount / embeddingDailyMax) * 100 : 0.0;

        return new AiLimitStatusResponse(
                dateStr,
                chatCount,
                chatDailyMax,
                Math.min(100.0, Math.round(chatProgress * 100.0) / 100.0),
                embedCount,
                embeddingDailyMax,
                Math.min(100.0, Math.round(embedProgress * 100.0) / 100.0),
                finalCostUsd,
                finalCostTwd);
    }

    private record ChatContext(
            AiSession session,
            List<Message> springAiMessages,
            Expression filterExpression,
            String promptTemplate) {}
}
