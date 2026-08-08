package com.hys.classcord.auth.service.oauth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class GoogleAuthStrategy implements OAuth2Strategy {
    private final GoogleIdTokenVerifier verifier;
    private final String clientId;
    private final String clientSecret;
    private final String frontendUrl;
    private final RestClient restClient;

    public GoogleAuthStrategy(
            GoogleIdTokenVerifier verifier,
            @Value(
                            "${spring.security.oauth2.client.registration.google.client-id:mock-google-client-id}")
                    String googleClientId,
            @Value(
                            "${spring.security.oauth2.client.registration.google.client-secret:mock-google-client-secret}")
                    String googleClientSecret,
            @Value("${app.urls.frontend}") String frontendUrl,
            RestClient restClient) {
        this.verifier = verifier;
        this.clientId = googleClientId;
        this.clientSecret = googleClientSecret;
        this.frontendUrl = frontendUrl;
        this.restClient = restClient;
    }

    @Override
    public AuthProvider getProvider() {
        return AuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserInfoDto verifyAndExtractInfo(String code) {
        String idTokenString;
        try {
            // 階段一：向 Google 伺服器以 authorization_code 交換 tokens (包含 id_token)
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("code", code);
            formData.add("grant_type", "authorization_code");
            formData.add("redirect_uri", frontendUrl + "/login");

            Map<String, Object> tokenResponse =
                    restClient
                            .post()
                            .uri("https://oauth2.googleapis.com/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(formData)
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (tokenResponse == null || !tokenResponse.containsKey("id_token")) {
                throw new AuthException(
                        AuthErrorCode.OAUTH_PROVIDER_ERROR, "無法從 Google 交換取得 id_token");
            }
            idTokenString = (String) tokenResponse.get("id_token");
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(
                    AuthErrorCode.OAUTH_PROVIDER_ERROR,
                    "調用 Google Token 交換介面失敗: " + e.getMessage());
        }

        // 階段二：驗證 id_token (JWT) 合法性
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
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
