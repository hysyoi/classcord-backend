package com.hys.classcord.auth.service.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GoogleAuthStrategy implements OAuth2Strategy {
    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthStrategy(
            @Value("${spring.security.oauth2.client.registration.google.client-id}")
                    String googleClientId) {
        this.verifier =
                new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                        .setAudience(Collections.singletonList(googleClientId))
                        .build();
    }

    @Override
    public AuthProvider getProvider() {
        return AuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserInfoDto verifyAndExtractInfo(String token) {
        GoogleIdToken idToken;

        try {
            idToken = verifier.verify(token);
        } catch (Exception e) {
            throw new AuthException(
                    AuthErrorCode.TOKEN_EXPIRED_OR_INVALID, "Google 驗證服務連線異常: " + e.getMessage());
        }

        if (idToken == null) {
            throw new AuthException(
                    AuthErrorCode.TOKEN_EXPIRED_OR_INVALID, "Google 驗證失敗：憑證不合法或已過期");
        }

        try {
            GoogleIdToken.Payload payload = idToken.getPayload();
            return new OAuthUserInfoDto(
                    payload.getSubject(),
                    payload.getEmail(),
                    (String) payload.get("name"),
                    (String) payload.get("picture"),
                    AuthProvider.GOOGLE,
                    payload.getEmailVerified());
        } catch (Exception e) {
            throw new AuthException(
                    AuthErrorCode.TOKEN_EXPIRED_OR_INVALID, "解析 Google 用戶資訊封包時發生異常");
        }
    }
}
