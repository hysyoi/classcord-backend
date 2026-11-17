package com.hys.classcord.core.config;

import com.hys.classcord.auth.enums.AuthProvider;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 全專案共用的 Apache HttpClient 連線池（單例）。
     *
     * <p>連線池是真正耗資源的部分，所以只建一份，所有 RestClient.Builder 都接到這個連線池上。
     */
    @Bean
    public HttpComponentsClientHttpRequestFactory pooledRequestFactory() {
        // 1. 建立 Apache HttpClient 連線池管理器
        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // 整個連線池最大允許 100 個連線
        connectionManager.setDefaultMaxPerRoute(20); // 每個目標域名最大允許 20 個連線

        // 使用 ConnectionConfig 設定連線逾時
        ConnectionConfig connectionConfig =
                ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(5000)).build();
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        // 2. 設定讀取逾時配置（ResponseTimeout 逾時延展至 30 秒以容納 Gemini 生成延遲）
        RequestConfig requestConfig =
                RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(30000)).build();
        // 3. 構建支援連線池的 CloseableHttpClient
        CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .build();
        // 4. 包裝成 Spring 認得的 HttpComponentsClientHttpRequestFactory
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    /**
     * RestClient.Builder 必須是 prototype：每個注入點各拿一個新的 Builder，底下共用同一個連線池。
     *
     * <p>Builder 是可變物件，呼叫 baseUrl()、defaultHeaders() 會直接改動它本身。若做成單例，任何一個使用者在 Builder 上設定的
     * baseUrl、header，都會被之後才 build 的 RestClient 繼承（ai-service 曾因此被 Spring AI 帶入 Bearer header 而呼叫
     * Gemini 失敗）。Spring Boot 內建的 RestClient.Builder 同樣是 prototype。
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(
            HttpComponentsClientHttpRequestFactory pooledRequestFactory) {
        return RestClient.builder().requestFactory(pooledRequestFactory);
    }

    /** 全域共用的 RestClient Bean 讓全專案的 Service 都能直接注入，避免重複構建連線工具 */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        configurer.setDefaultTimeout(300_000); // 5 分鐘，避免 AI 串流回應較長時連線被中斷
    }

    @Bean
    public AsyncTaskExecutor mvcTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true); // 使用 Java 21 虛擬執行緒
        executor.setThreadNamePrefix("mvc-virtual-");
        return executor;
    }

    /** 核心：註冊全局的 URL 路徑參數轉換器 繼承 WebMvcConfigurer 並重寫 addFormatters */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 將匿名內部類別重構為乾淨的 Lambda
        registry.addConverter(
                String.class,
                AuthProvider.class,
                source -> source.isBlank() ? null : AuthProvider.valueOf(source.toUpperCase()));
    }
}
