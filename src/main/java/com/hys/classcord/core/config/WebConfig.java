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
     * 註冊 RestClient.Builder 為 Bean，使 Spring Boot 自動配置（包含 Spring AI） 都會自動以此為模板，套用配置好的 Apache
     * HttpClient 連線池。
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
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
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(requestFactory);
    }

    /** 全域共用的 RestClient Bean 讓全專案的 Service 都能直接注入，避免重複構建連線工具 */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        configurer.setDefaultTimeout(300_000); // 5 分鐘！防止 AI 吐字過長導致連線中斷
    }

    @Bean
    public AsyncTaskExecutor mvcTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true); // 啟用 Java 21 虛擬線程！
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
