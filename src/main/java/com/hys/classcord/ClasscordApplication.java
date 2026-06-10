package com.hys.classcord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class}) // 強行關閉預設密碼建置機制
@EnableAsync // 開啟 Spring 的非同步任務支援
public class ClasscordApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClasscordApplication.class, args);
    }
}
