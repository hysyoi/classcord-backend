package com.hys.classcord.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.turnstile")
@Getter
@Setter
public class TurnstileProperties {
    private boolean enabled;
    private String siteKey;
    private String secretKey;
}
