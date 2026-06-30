package com.hys.classcord.ai.service;

import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private final MaterialRepository materialRepository;
    private final RabbitTemplate rabbitTemplate;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

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

        // 2. 發送 MQ 消息至 RabbitMQ 佇列
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.AI_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_RAG_PROCESS,
                materialId.toString());
        log.info("已成功推送 RAG 處理消息至 RabbitMQ: materialId={}", materialId);
    }

    /** 核心 RAG 對話邏輯 */
    @Transactional(readOnly = true)
    public String chatWithMaterial(UUID materialId, String userMessage) {
        // 1. 檢驗教材狀態
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        if (material.getStatus() != MaterialStatus.ENABLED) {
            throw new MaterialException(
                    MaterialErrorCode.AI_ASSISTANT_PROCESSING, "該教材尚未完成 AI 助教啟用，暫無法回答");
        }

        // 2. 建立多租戶向量過濾條件
        var filterExpression =
                new FilterExpressionBuilder().eq("material_id", materialId.toString()).build();

        // 3. 呼叫 Spring AI 執行檢索與問答 (使用 Builder 建立 SearchRequest)
        ChatClient chatClient = chatClientBuilder.build();

        return chatClient
                .prompt()
                .user(userMessage)
                .advisors(
                        new QuestionAnswerAdvisor(
                                vectorStore,
                                SearchRequest.builder()
                                        .query(userMessage)
                                        .filterExpression(filterExpression)
                                        .build()))
                .call()
                .content();
    }
}
