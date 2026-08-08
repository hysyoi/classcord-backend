package com.hys.classcord.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hys.classcord.ai.client.QuizClient;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.MaterialChunkRepository;
import com.hys.classcord.ai.strategy.MaterialSlicingStrategy;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

// 只測 analyzeDoubt：邏輯完整、不涉及背景執行緒或全站限流迴圈。
// generateQuestions 整體屬於「重度呼叫 AI + 併行執行緒」的編排邏輯，投報率低，這裡不測。
@ExtendWith(MockitoExtension.class)
class QuizAiServiceTest {

    @Mock private MaterialChunkRepository materialChunkRepository;
    @Mock private AiMessageRepository aiMessageRepository;
    @Mock private QuizClient quizClient;
    @Mock private ChatClient chatClient;
    @Mock private MaterialSlicingStrategy slicingStrategy;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<Long> rateLimitScript;

    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private QuizAiService quizAiService;

    @BeforeEach
    void setUp() {
        quizAiService =
                new QuizAiService(
                        materialChunkRepository,
                        aiMessageRepository,
                        quizClient,
                        chatClient,
                        slicingStrategy,
                        redisTemplate,
                        rateLimitScript);
        ReflectionTestUtils.setField(
                quizAiService,
                "doubtPromptResource",
                new ClassPathResource("prompts/class-doubt-analysis.st"));
    }

    @SuppressWarnings("unchecked")
    private void stubChatClientResponse(String jsonResponse) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(jsonResponse);
    }

    // 學生完全沒有提問紀錄時，不該浪費 Token 呼叫 AI，直接回傳空結果
    @Test
    void analyzeDoubt_returnsEmptyResult_whenNoUserMessagesExist() {
        UUID materialId = UUID.randomUUID();
        when(aiMessageRepository.findUserMessagesByMaterialId(eq(materialId), any(Limit.class)))
                .thenReturn(List.of());

        var result = quizAiService.analyzeDoubt(materialId);

        assertThat(result.totalQuestionsAnalyzed()).isZero();
        assertThat(result.themes()).isEmpty();
        verifyNoInteractions(chatClient);
    }

    // 有提問紀錄時，應該呼叫 AI 進行結構化分析，並用實際的提問數量覆蓋 AI 回傳的統計數字
    @Test
    void analyzeDoubt_returnsParsedThemes_whenUserMessagesExist() {
        UUID materialId = UUID.randomUUID();
        List<String> userMessages = List.of("這題怎麼解？", "公式忘記了");
        when(aiMessageRepository.findUserMessagesByMaterialId(eq(materialId), any(Limit.class)))
                .thenReturn(userMessages);
        stubChatClientResponse(
                "{\"totalQuestionsAnalyzed\":0,\"themes\":[{\"themeName\":\"二次方程式\","
                        + "\"description\":\"學生對公式推導不熟\",\"questions\":[\"這題怎麼解？\"],"
                        + "\"recommendation\":\"複習公式推導過程\"}]}");

        var result = quizAiService.analyzeDoubt(materialId);

        assertThat(result.totalQuestionsAnalyzed()).isEqualTo(2); // 用實際提問數，不是 AI 回傳的數字
        assertThat(result.themes()).hasSize(1);
        assertThat(result.themes().get(0).themeName()).isEqualTo("二次方程式");
    }

    // AI 沒有回傳任何內容時，應該拋出明確的業務例外，而不是讓後續解析拋 NPE 或格式錯誤一路往上炸
    @Test
    void analyzeDoubt_throwsAiException_whenAiReturnsBlankResponse() {
        UUID materialId = UUID.randomUUID();
        when(aiMessageRepository.findUserMessagesByMaterialId(eq(materialId), any(Limit.class)))
                .thenReturn(List.of("有問題"));
        stubChatClientResponse("   ");

        assertThatThrownBy(() -> quizAiService.analyzeDoubt(materialId))
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.AI_ASSISTANT_PROCESSING.getCode());
    }
}
