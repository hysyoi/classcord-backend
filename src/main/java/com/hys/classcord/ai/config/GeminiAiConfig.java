package com.hys.classcord.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Gemini AI 自訂嵌入配置，將我們自訂的 GeminiEmbeddingModel 註冊為 Bean，自動替換掉預設的 OpenAiEmbeddingModel。 */
@Configuration
public class GeminiAiConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.embedding.options.model:gemini-embedding-001}")
    private String modelName;

    /** 註冊我們自訂的 EmbeddingModel，並注入 WebConfig 中已經配置好連線池與逾時的 restClient。 */
    @Bean
    public EmbeddingModel embeddingModel(RestClient restClient) {
        return new GeminiEmbeddingModel(restClient, apiKey, modelName);
    }
}
