package com.hys.classcord.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.hys.classcord")
@EntityScan(basePackages = "com.hys.classcord")
@EnableJpaRepositories(basePackages = "com.hys.classcord")
@EnableJpaAuditing
@EnableAsync
@EnableScheduling // 開啟 @Scheduled 定時任務支援（AI 使用量指標定期刷新用）
@ConfigurationPropertiesScan(basePackages = "com.hys.classcord")
@SpringBootApplication(
        scanBasePackages = "com.hys.classcord",
        exclude = {UserDetailsServiceAutoConfiguration.class})
public class ClasscordAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClasscordAiApplication.class, args);
    }
}
