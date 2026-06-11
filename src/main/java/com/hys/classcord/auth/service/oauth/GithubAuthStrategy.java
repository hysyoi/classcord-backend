package com.hys.classcord.auth.service.oauth;


import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class GithubAuthStrategy implements OAuth2Strategy {

    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;

    public GithubAuthStrategy(
            @Value("${spring.security.oauth2.client.registration.github.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.github.client-secret}") String clientSecret,
            RestClient restClient) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = restClient;
    }

    @Override
    public AuthProvider getProvider() {
        return AuthProvider.GITHUB;
    }

    @Override
    public OAuthUserInfoDto verifyAndExtractInfo(String code) {
        String accessToken;
        try {
            // 改用表單物件封裝，杜絕 422 格式錯誤
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("code", code);

            // 階段一：向 GitHub 伺服器交換 access_token
            Map<String, Object> tokenResponse = restClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED) // 1. 用表單格式發送
                    .accept(MediaType.APPLICATION_JSON)                 // 2. 強制要求 GitHub 回傳 JSON 回應
                    .body(formData)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                throw new AuthException(AuthErrorCode.TOKEN_EXPIRED_OR_INVALID, "GitHub 授權碼無效或已過期");
            }

            accessToken = (String) tokenResponse.get("access_token");

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, "連線至 GitHub 交換 Token 失敗: " + e.getMessage());
        }

        // 階段二：撈取個人資料
        Map<String, Object> userProfile;
        try {
            userProfile = restClient.get()
                    .uri("https://api.github.com/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (userProfile == null) {
                throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, "無法取得 GitHub 用戶原始資料");
            }
        } catch (Exception e) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, "調用 GitHub 用戶資訊接口失敗: " + e.getMessage());
        }

        String githubUid = String.valueOf(userProfile.get("id"));
        String email = (String) userProfile.get("email");
        String username = (String) userProfile.get("login");
        String avatarUrl = (String) userProfile.get("avatar_url");

        // 降級防禦：撈取私密信箱
        if (email == null || email.isBlank()) {
            try {
                List<Map<String, Object>> emailList = restClient.get()
                        .uri("https://api.github.com/user/emails")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

                if (emailList != null && !emailList.isEmpty()) {
                    email = emailList.stream()
                            .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
                            .map(e -> (String) e.get("email"))
                            .findFirst()
                            .orElse(null);
                }
            } catch (Exception e) {
                throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, "嘗試撈取 GitHub 私密信箱時連線異常");
            }
        }

        // 終極防線：拿不到 Email
        if (email == null || email.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_EMAIL_NOT_FOUND, "您的 GitHub 帳號未綁定或未公開已驗證的 Email");
        }

        return new OAuthUserInfoDto(githubUid, email, username, avatarUrl, AuthProvider.GITHUB, true);
    }
}