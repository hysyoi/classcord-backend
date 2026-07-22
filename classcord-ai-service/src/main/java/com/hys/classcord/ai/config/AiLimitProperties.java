package com.hys.classcord.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ai.limit")
public class AiLimitProperties {
    private int chatDailyMax = 400;
    private int embeddingDailyMax = 3000;
}
