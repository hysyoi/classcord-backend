package com.hys.classcord.auth.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleAuthConfig {

    // 獨立宣告為 Bean 讓 GoogleAuthStrategy 能以建構子注入，測試時才能 mock 掉驗證成功路徑
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            @Value(
                            "${spring.security.oauth2.client.registration.google.client-id:mock-google-client-id}")
                    String googleClientId) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }
}
