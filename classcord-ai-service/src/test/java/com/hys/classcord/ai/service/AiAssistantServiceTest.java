package com.hys.classcord.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hys.classcord.ai.client.MaterialClient;
import com.hys.classcord.ai.config.AiLimitProperties;
import com.hys.classcord.ai.entity.AiMessage;
import com.hys.classcord.ai.entity.AiSession;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.enums.AiMessageRole;
import com.hys.classcord.ai.exception.AiException;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.AiSessionRepository;
import com.hys.classcord.ai.strategy.RagStrategyFactory;
import com.hys.classcord.common.dto.InternalMaterialDto;
import com.hys.classcord.material.enums.MaterialStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

// 純單元測試：不啟動 Spring context、不真的呼叫 Gemini。
// 只測不涉及 ChatClient/VectorStore 實際對話呼叫的部分：getAiLimitStatus 的純計算邏輯，
// 以及 createSession/getSessionMessages 開頭的權限與狀態檢查。chatInSession 本身要 mock
// Spring AI 的 ChatClient fluent API，複雜度堪比 RestClient 那種鏈式呼叫、投報率低，這裡不測。
@ExtendWith(MockitoExtension.class)
class AiAssistantServiceTest {

    @Mock private MaterialClient materialClient;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private VectorStore vectorStore;
    @Mock private ChatClient chatClient;
    @Mock private AiSessionRepository aiSessionRepository;
    @Mock private AiMessageRepository aiMessageRepository;
    @Mock private RagStrategyFactory strategyFactory;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private final AiLimitProperties aiLimitProperties = new AiLimitProperties();

    private AiAssistantService aiAssistantService;

    @BeforeEach
    void setUp() {
        aiLimitProperties.setChatDailyMax(400);
        aiLimitProperties.setEmbeddingDailyMax(3000);

        aiAssistantService =
                new AiAssistantService(
                        materialClient,
                        rabbitTemplate,
                        vectorStore,
                        chatClient,
                        aiSessionRepository,
                        aiMessageRepository,
                        strategyFactory,
                        transactionTemplate,
                        redisTemplate,
                        aiLimitProperties);
    }

    // ==========================================
    // getAiLimitStatus
    // ==========================================

    // 今天完全還沒用過，應該顯示 0 次、0% 進度、0 成本，而不是因為 Redis 讀不到值就出錯
    @Test
    void getAiLimitStatus_returnsZero_whenNoUsageToday() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        var status = aiAssistantService.getAiLimitStatus();

        assertThat(status.chatCount()).isEqualTo(0L);
        assertThat(status.chatProgress()).isEqualTo(0.0);
        assertThat(status.estimatedCostUsd()).isEqualTo(0.0);
    }

    // 使用量超過每日上限時，進度應該封頂在 100%，成本計算也只該算到上限為止，
    // 不能讓超額的無效呼叫繼續墊高看起來的花費
    @Test
    void getAiLimitStatus_capsProgressAndCost_whenUsageExceedsDailyMax() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(
                        "ai:all-site:limit:"
                                + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))))
                .thenReturn("450"); // 超過 400 上限
        when(valueOperations.get(
                        "ai:all-site:embedding-limit:"
                                + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))))
                .thenReturn("0");

        var status = aiAssistantService.getAiLimitStatus();

        assertThat(status.chatCount()).isEqualTo(450L); // 原始次數如實顯示
        assertThat(status.chatProgress()).isEqualTo(100.0); // 但進度封頂在 100%
        // 成本只算到上限 400 次：400 * 0.00165 = 0.66 USD
        assertThat(status.estimatedCostUsd()).isEqualTo(0.66);
    }

    // ==========================================
    // createSession
    // ==========================================

    // 教材還沒完成 AI 助教啟用，不能開新對話
    @Test
    void createSession_throwsAiAssistantProcessing_whenMaterialNotEnabled() {
        UUID materialId = UUID.randomUUID();
        when(materialClient.getMaterial(materialId))
                .thenReturn(
                        InternalMaterialDto.builder()
                                .id(materialId)
                                .status(MaterialStatus.PROCESSING)
                                .build());

        assertThatThrownBy(() -> aiAssistantService.createSession(UUID.randomUUID(), materialId))
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.AI_ASSISTANT_PROCESSING.getCode());
    }

    // 教材已啟用，應該正常建立新的對話會話
    @Test
    void createSession_succeeds_whenMaterialIsEnabled() {
        UUID materialId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(materialClient.getMaterial(materialId))
                .thenReturn(
                        InternalMaterialDto.builder()
                                .id(materialId)
                                .status(MaterialStatus.ENABLED)
                                .build());
        when(aiSessionRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            AiSession session = invocation.getArgument(0);
                            session.setId(UUID.randomUUID());
                            return session;
                        });

        AiSession result = aiAssistantService.createSession(userId, materialId);

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getMaterialId()).isEqualTo(materialId);
    }

    // ==========================================
    // getSessionMessages
    // ==========================================

    // 對話會話不存在，應該拒絕
    @Test
    void getSessionMessages_throwsSessionNotFound_whenSessionDoesNotExist() {
        UUID sessionId = UUID.randomUUID();
        when(aiSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> aiAssistantService.getSessionMessages(UUID.randomUUID(), sessionId))
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.SESSION_NOT_FOUND.getCode());
    }

    // 別人的對話紀錄，不能偷看
    @Test
    void getSessionMessages_throwsSessionAccessDenied_whenNotTheOwner() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AiSession session =
                AiSession.builder().userId(ownerId).materialId(UUID.randomUUID()).build();
        when(aiSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(
                        () -> aiAssistantService.getSessionMessages(UUID.randomUUID(), sessionId))
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.SESSION_ACCESS_DENIED.getCode());
    }

    // repository 回傳的是「新到舊」排序，這裡應該把它反轉成「舊到新」的時間順序再回傳
    @Test
    void getSessionMessages_returnsMessagesInChronologicalOrder() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AiSession session =
                AiSession.builder().userId(userId).materialId(UUID.randomUUID()).build();
        when(aiSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        AiMessage newest =
                AiMessage.builder()
                        .session(session)
                        .role(AiMessageRole.ASSISTANT)
                        .content("newest")
                        .build();
        AiMessage oldest =
                AiMessage.builder()
                        .session(session)
                        .role(AiMessageRole.USER)
                        .content("oldest")
                        .build();
        // repository 依 createdAt DESC 排序：新到舊
        when(aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(newest, oldest));

        List<AiMessage> result = aiAssistantService.getSessionMessages(userId, sessionId);

        assertThat(result).containsExactly(oldest, newest);
    }
}
