package com.hys.classcord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class}) // 強行關閉預設密碼建置機制
@EnableAsync // 開啟 Spring 的非同步任務支援
@EnableJpaAuditing // 開啟 JPA 審計功能
@EnableDiscoveryClient
@EnableFeignClients
@ConfigurationPropertiesScan // 啟動全局的 @ConfigurationProperties 自動掃描與註冊
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class ClasscordMainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClasscordMainApplication.class, args);
    }
}
