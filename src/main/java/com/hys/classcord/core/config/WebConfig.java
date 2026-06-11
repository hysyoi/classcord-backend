package com.hys.classcord.core.config;

import com.hys.classcord.auth.enums.AuthProvider;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 全域共用的 RestClient Bean 讓全專案的 Service 都能直接注入，避免重複構建連線工具，未來也方便在此統一配置超時或攔截器 */
    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
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
