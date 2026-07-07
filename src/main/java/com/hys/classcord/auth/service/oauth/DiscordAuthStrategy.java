package com.hys.classcord.auth.service.oauth;

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
public class DiscordAuthStrategy implements OAuth2Strategy {

    private final String frontendUrl;
    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;

    public DiscordAuthStrategy(
            @Value("${app.urls.frontend}") String frontendUrl,
            @Value(
                            "${spring.security.oauth2.client.registration.discord.client-id:mock-discord-client-id}")
                    String clientId,
            @Value(
                            "${spring.security.oauth2.client.registration.discord.client-secret:mock-discord-client-secret}")
                    String clientSecret,
            RestClient restClient) {
        this.frontendUrl = frontendUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = restClient;
    }

    @Override
    public AuthProvider getProvider() {
        return AuthProvider.DISCORD;
    }

    @Override
    public OAuthUserInfoDto verifyAndExtractInfo(String code) {
        String accessToken;
        try {
            // Discord 特性：換 Token 強制要求必須使用表單格式（x-www-form-urlencoded）
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("client_id", clientId);
            formData.add("client_secret", clientSecret);
            formData.add("grant_type", "authorization_code");
            formData.add("code", code);
            // Discord 嚴格校驗：Redirect URI 必須與授權請求時使用的完全一致
            formData.add("redirect_uri", frontendUrl + "/login");

            // 階段一：向 Discord 伺服器交換 access_token
            Map<String, Object> tokenResponse =
                    restClient
                            .post()
                            .uri("https://discord.com/api/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(formData)
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                throw new AuthException(
                        AuthErrorCode.TOKEN_EXPIRED_OR_INVALID, "Discord 授權碼無效或已過期");
            }

            accessToken = (String) tokenResponse.get("access_token");

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(
                    AuthErrorCode.OAUTH_PROVIDER_ERROR,
                    "連線至 Discord 交換 Token 失敗: " + e.getMessage());
        }

        try {
            // 階段二：拿著 access_token 去打 Discord 專屬的 API 撈取用戶個人隱私資料
            Map<String, Object> userProfile =
                    restClient
                            .get()
                            .uri("https://discord.com/api/users/@me") // 撈取「當前登入者」的接口
                            .header("Authorization", "Bearer " + accessToken)
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (userProfile == null) {
                throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, "無法取得 Discord 用戶原始資料");
            }

            String discordUid = (String) userProfile.get("id");
            String email = (String) userProfile.get("email");
            String username = (String) userProfile.get("username");
            String avatarHash = (String) userProfile.get("avatar");
            boolean verified = Boolean.TRUE.equals(userProfile.get("verified"));

            // 安全防線：Discord 允許沒綁定 Email 的帳號存在，但Classcord 不收，直接拒絕
            if (email == null || email.isBlank()) {
                throw new AuthException(
                        AuthErrorCode.OAUTH_EMAIL_NOT_FOUND, "您的 Discord 帳號未綁定任何 Email，無法完成註冊");
            }

            // 動態拼接 Discord 的 CDN 大頭貼 URL 規則
            String avatarUrl = null;
            if (avatarHash != null) {
                avatarUrl =
                        String.format(
                                "https://cdn.discordapp.com/avatars/%s/%s.png",
                                discordUid, avatarHash);
            }

            return new OAuthUserInfoDto(
                    discordUid, email, username, avatarUrl, AuthProvider.DISCORD, verified);

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(
                    AuthErrorCode.OAUTH_PROVIDER_ERROR, "調用 Discord 用戶資訊接口失敗: " + e.getMessage());
        }
    }
}
