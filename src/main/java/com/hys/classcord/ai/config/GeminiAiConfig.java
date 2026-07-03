package com.hys.classcord.ai.config;

import org.springframework.ai.chat.client.ChatClient;
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

    /**
     * 將 ChatClient 統一 build 一次並交由 Spring 容器管理。 ChatClient 是 Thread-safe 的，可安全共享。 測試環境可直接用 @MockBean
     * ChatClient 替換此 Bean。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
