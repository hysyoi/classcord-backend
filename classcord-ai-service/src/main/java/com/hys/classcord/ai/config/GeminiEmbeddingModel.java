package com.hys.classcord.ai.config;

import com.hys.classcord.ai.service.AiRateLimitService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.web.client.RestClient;

/** 自訂 Gemini 向量嵌入模型，使用 Google 官方原生 batchEmbedContents 批次 API 處理多段切片向量化。 */
@Slf4j
public class GeminiEmbeddingModel extends AbstractEmbeddingModel {

    private static final int BATCH_LIMIT = 100; // Google Gemini batchEmbedContents 單次最大批次限制
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

    private void checkRateLimit(int batchSize) {
        rateLimitService.checkEmbeddingRateLimit(batchSize);
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        return this.embed(document.getFormattedContent());
    }

    @Override
    @SuppressWarnings("unchecked")
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return new EmbeddingResponse(Collections.emptyList(), new EmbeddingResponseMetadata());
        }

        int totalSize = instructions.size();
        checkRateLimit(totalSize);
        List<Embedding> embeddings = new ArrayList<>();
        int globalIndex = 0;

        // 分批次處理（每批次最多 100 條，將 N 次 HTTP 往返降為 1 次）
        for (int i = 0; i < totalSize; i += BATCH_LIMIT) {
            List<String> batchInstructions =
                    instructions.subList(i, Math.min(i + BATCH_LIMIT, totalSize));
            List<Map<String, Object>> requestsList = new ArrayList<>();

            for (String text : batchInstructions) {
                requestsList.add(
                        Map.of(
                                "model",
                                "models/" + modelName,
                                "content",
                                Map.of("parts", List.of(Map.of("text", text))),
                                "outputDimensionality",
                                768));
            }

            Map<String, Object> body = Map.of("requests", requestsList);

            // 發送單次批次 POST 請求至 Google 官方 batchEmbedContents 端點 (帶 x-goog-api-key Header)
            Map<String, Object> response =
                    restClient
                            .post()
                            .uri(
                                    "https://generativelanguage.googleapis.com/v1beta/models/"
                                            + modelName
                                            + ":batchEmbedContents")
                            .header("x-goog-api-key", apiKey)
                            .body(body)
                            .retrieve()
                            .body(Map.class);

            if (response == null || !response.containsKey("embeddings")) {
                throw new IllegalStateException(
                        "Google Gemini Batch Embedding API 回傳異常結果: " + response);
            }

            List<Map<String, Object>> embeddingsList =
                    (List<Map<String, Object>>) response.get("embeddings");
            for (Map<String, Object> embeddingMap : embeddingsList) {
                List<Double> values = (List<Double>) embeddingMap.get("values");

                float[] floatValues = new float[values.size()];
                for (int j = 0; j < values.size(); j++) {
                    floatValues[j] = values.get(j).floatValue();
                }

                embeddings.add(new Embedding(floatValues, globalIndex++));
            }
        }

        log.info(
                "【Gemini Embedding】成功批次生成 {} 筆向量 (總共呼叫 {} 次 HTTP 請求)",
                totalSize,
                (int) Math.ceil((double) totalSize / BATCH_LIMIT));
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }
}
