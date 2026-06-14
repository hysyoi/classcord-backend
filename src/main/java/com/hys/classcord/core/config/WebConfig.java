package com.hys.classcord.core.config;

import com.hys.classcord.auth.enums.AuthProvider;
import java.util.Optional;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 全域共用的 RestClient Bean 讓全專案的 Service 都能直接注入，避免重複構建連線工具，未來也方便在此統一配置超時或攔截器 */
    @Bean
    public RestClient restClient() {
        // 1. 建立 Apache HttpClient 連線池管理器
        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100); // 整個連線池最大允許 100 個連線
        connectionManager.setDefaultMaxPerRoute(20); // 每個目標域名（例如 api.github.com）最大允許 20 個連線
        // 2. 設定逾時配置（連線逾時 5 秒，讀取逾時 5 秒）
        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(5000))
                        .setResponseTimeout(Timeout.ofMilliseconds(5000))
                        .build();
        // 3. 構建支援連線池的 CloseableHttpClient
        CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .build();
        // 4. 包裝成 Spring 認得的 HttpComponentsClientHttpRequestFactory
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 核心黑魔法：註冊全局的 URL 路徑參數轉換器 繼承 WebMvcConfigurer 並重寫 addFormatters */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(
                new Converter<String, AuthProvider>() {
                    @Override
                    public AuthProvider convert(@Nullable String source) {
                        return Optional.ofNullable(source)
                                .filter(str -> !str.isBlank())
                                .map(str -> AuthProvider.valueOf(str.toUpperCase()))
                                .orElse(null); // 如果是 null 或空字串，回傳 null
                    }
                });
    }
}
