package com.hys.classcord.ai.config;

import com.hys.classcord.ai.service.AiRateLimitService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.web.client.RestClient;

/** 自訂 Gemini 向量嵌入模型，直接呼叫 Google 官方原生 API 以避開 OpenAI 相容層缺失 usage 欄位導致的 NullPointerException。 */
@Slf4j
public class GeminiEmbeddingModel extends AbstractEmbeddingModel {

    private final RestClient restClient;
    private final String apiKey;
    private final String modelName;
    private final AiRateLimitService rateLimitService;

    public GeminiEmbeddingModel(
            RestClient restClient,
            String apiKey,
            String modelName,
            AiRateLimitService rateLimitService) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.rateLimitService = rateLimitService;
    }

    private void checkRateLimit() {
        rateLimitService.checkEmbeddingRateLimit();
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
