package com.hys.classcord.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.urls")
@Getter
@Setter
public class AppUrlProperties {
    private String backend;
    private String frontend;
}
