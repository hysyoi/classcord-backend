package com.hys.classcord.ai.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.ai.client.QuizClient;
import com.hys.classcord.ai.entity.MaterialChunk;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.MaterialChunkRepository;
import com.hys.classcord.ai.strategy.MaterialSlicingStrategy;
import com.hys.classcord.common.dto.FailMaterialRequest;
import com.hys.classcord.quiz.dto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuizAiService {

    private final MaterialChunkRepository materialChunkRepository;
    private final AiMessageRepository aiMessageRepository;
    private final QuizClient quizClient;
    private final ChatClient chatClient;
    private final MaterialSlicingStrategy slicingStrategy;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    @Value("classpath:prompts/quiz-single-choice-generation.st")
    private Resource promptResource;

    @Value("classpath:prompts/class-doubt-analysis.st")
    private Resource doubtPromptResource;

    private final ObjectMapper quizObjectMapper =
            new ObjectMapper()
                    .configure(
                            JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(),
                            true)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 背景執行 AI 出題：分段、限流、併行呼叫 LLM，並將結果透過 OpenFeign 傳回主服務 (100% 保留原版邏輯與註解) */
    public void generateQuestions(QuizGenerationMessage message) {
        UUID jobId = message.jobId();
        UUID materialId = message.materialId();
        int count = message.count();
        String difficulty = message.difficulty();

        try {
            List<MaterialChunk> chunks =
                    materialChunkRepository.findByMaterialId(materialId.toString());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("該教材無切片內容，無法出題，請確認教材是否正常解析");
            }

            log.info(
                    "【AI 微服務】開始背景出題任務: jobId={}, materialId={}, count={}",
                    jobId,
                    materialId,
                    count);

            List<String> contexts = slicingStrategy.slice(chunks, count);

            List<GeneratedQuestionDto> dtos;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<GeneratedQuestionDto>> futures =
                        contexts.stream()
                                .map(
                                        context ->
                                                CompletableFuture.supplyAsync(
                                                        () -> {
                                                            acquireGeminiRateLimitPermit(jobId);
                                                            return generateSingleQuestion(
                                                                    context, difficulty);
                                                        },
                                                        executor))
                                .toList();

                dtos = futures.stream().map(CompletableFuture::join).toList();
            }

            quizClient.completeJob(jobId, new SaveGeneratedQuestionsRequest(jobId, materialId, dtos));
            log.info("【AI 微服務】背景出題成功並已通知主服務: jobId={}, 共 {} 題", jobId, count);

        } catch (Exception e) {
            log.error("【AI 微服務】AI 出題背景生成任務失敗 jobId=" + jobId, e);
            quizClient.markJobAsFailed(
                    jobId, materialId, new FailMaterialRequest(e.getMessage()));
        }
    }

    /** 疑難點 AI 分析：從 AI 消息資料庫查詢學生提問紀錄，並呼叫 LLM 進行結構化 JSON 分析 (100% 保留原版邏輯與註解) */
    public ClassDoubtResponse analyzeDoubt(UUID materialId) {
        // 效能與資源保護：在呼叫 AI 前，先拉取與檢查學生的提問紀錄。
        // 若學生無任何提問紀錄，則直接回傳空主題，避免浪費 AI Token 或引發併發鎖與頻率限制開銷。
        List<String> userMessages =
                aiMessageRepository.findUserMessagesByMaterialId(materialId, Limit.of(50));
        if (userMessages.isEmpty()) {
            return new ClassDoubtResponse(0, Collections.emptyList());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < userMessages.size(); i++) {
            sb.append(i + 1).append(". ").append(userMessages.get(i)).append("\n");
        }
        String userMessagesText = sb.toString();

        var outputConverter =
                new BeanOutputConverter<>(ClassDoubtResponse.class, quizObjectMapper);
        String formatSpec = outputConverter.getFormat();

        String promptText;
        try {
            promptText = doubtPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("無法加載 AI 班級疑問分析提示詞範本", e);
            throw new IllegalStateException("無法讀取疑問分析提示詞範本", e);
        }

        String response =
                chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(promptText)
                                                .param("userMessages", userMessagesText)
                                                .param("format_spec", formatSpec))
                        .call()
                        .content();

        if (response == null || response.isBlank()) {
            throw new AiException(AiErrorCode.AI_ASSISTANT_PROCESSING, "AI 服務未回傳任何疑問分析內容");
        }

        ClassDoubtResponse analysisResult = outputConverter.convert(response);
        if (analysisResult == null) {
            throw new AiException(AiErrorCode.AI_ASSISTANT_PROCESSING, "無法將 AI 回傳內容轉換為 ClassDoubtResponse 格式");
        }
        return new ClassDoubtResponse(userMessages.size(), analysisResult.themes());
    }

    private void acquireGeminiRateLimitPermit(UUID jobId) {
        String redisKey = "RATE_LIMIT:GEMINI_API";
        int maxAttempts = 10;
        int waitTimeMs = 1000;
        int limitPerPeriod = 900;
        int expireSeconds = 60;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Long count =
                    redisTemplate.execute(
                            rateLimitScript,
                            Collections.singletonList(redisKey),
                            String.valueOf(expireSeconds));

            long currentCount = Optional.ofNullable(count).orElse(0L);
            if (currentCount <= limitPerPeriod) {
                return;
            }

            log.warn(
                    "全站 Gemini API 呼叫頻率已達上限 ({} / 60s)。任務 {} 正在進行第 {} 次重試等待...",
                    limitPerPeriod,
                    jobId,
                    attempt);

            if (attempt == maxAttempts) {
                throw new AiException(
                        AiErrorCode.EMBEDDING_LIMIT_EXCEEDED, "AI 出題服務排隊繁忙，超限重試次數已達上限，請稍後再試。");
            }

            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("全域限流排隊被中斷", e);
            }
        }
    }

    private GeneratedQuestionDto generateSingleQuestion(String context, String difficulty) {
        var outputConverter =
                new BeanOutputConverter<>(GeneratedQuestionDto.class, quizObjectMapper);
        String formatSpec = outputConverter.getFormat();

        String promptText;
        try {
            promptText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("無法讀取出題提示詞範本", e);
        }

        String response =
                chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(promptText)
                                                .param("context", context)
                                                .param("difficulty", difficulty)
                                                .param("format_spec", formatSpec))
                        .call()
                        .content();

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 服務未回傳任何出題內容");
        }
        return outputConverter.convert(response);
    }
}
