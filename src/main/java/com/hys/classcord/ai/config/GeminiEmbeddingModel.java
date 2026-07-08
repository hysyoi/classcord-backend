package com.hys.classcord.ai.config;

import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.client.RestClient;

/** 自訂 Gemini 向量嵌入模型，直接呼叫 Google 官方原生 API 以避開 OpenAI 相容層缺失 usage 欄位導致的 NullPointerException。 */
public class GeminiEmbeddingModel extends AbstractEmbeddingModel {

    private final RestClient restClient;
    private final String apiKey;
    private final String modelName;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final int embeddingDailyMax;

    public GeminiEmbeddingModel(
            RestClient restClient,
            String apiKey,
            String modelName,
            StringRedisTemplate redisTemplate,
            RedisScript<Long> rateLimitScript,
            int embeddingDailyMax) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.embeddingDailyMax = embeddingDailyMax;
    }

    private void checkRateLimit() {
        String key = "ai:all-site:embedding-limit:" + LocalDate.now();
        try {
            Long count =
                    redisTemplate.execute(
                            rateLimitScript,
                            Collections.singletonList(key),
                            String.valueOf(getSecondsUntilMidnight()));
            long currentCount = Optional.ofNullable(count).orElse(0L);
            if (currentCount > embeddingDailyMax) {
                throw new AiException(AiErrorCode.EMBEDDING_LIMIT_EXCEEDED);
            }
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException(AiErrorCode.EMBEDDING_LIMIT_EXCEEDED, "安全系統異常，暫時無法執行向量化");
        }
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = LocalDate.now().plusDays(1).atStartOfDay();
        // 加上 10 分鐘（600 秒）的安全緩衝時間，防範伺服器與 Redis 時鐘漂移或跨日邊界併發問題
        return Math.max(1, Duration.between(now, midnight).toSeconds() + 600);
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        return this.embed(document.getFormattedContent());
    }

    @Override
    @SuppressWarnings("unchecked")
    public EmbeddingResponse call(EmbeddingRequest request) {
        checkRateLimit();
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;

        for (String text : request.getInstructions()) {
            Map<String, Object> body =
                    Map.of(
                            "content",
                            Map.of("parts", List.of(Map.of("text", text))),
                            "outputDimensionality",
                            768);

            // 直接發送 POST 請求至 Google 官方原生的 embedContent 端點
            Map<String, Object> response =
                    restClient
                            .post()
                            .uri(
                                    "https://generativelanguage.googleapis.com/v1beta/models/"
                                            + modelName
                                            + ":embedContent?key="
                                            + apiKey)
                            .body(body)
                            .retrieve()
                            .body(Map.class);

            if (response == null || !response.containsKey("embedding")) {
                throw new IllegalStateException("Google Gemini Embedding API 回傳異常結果: " + response);
            }

            Map<String, Object> embeddingMap = (Map<String, Object>) response.get("embedding");
            List<Double> values = (List<Double>) embeddingMap.get("values");

            float[] floatValues = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                floatValues[i] = values.get(i).floatValue();
            }

            embeddings.add(new Embedding(floatValues, index++));
        }

        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }
}
