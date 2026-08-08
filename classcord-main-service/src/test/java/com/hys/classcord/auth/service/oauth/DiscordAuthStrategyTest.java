package com.hys.classcord.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

// 純單元測試：不真的打 Discord API，用 Mockito 假扮 RestClient 的整條 fluent 呼叫鏈。
@ExtendWith(MockitoExtension.class)
class DiscordAuthStrategyTest {

    @Mock private RestClient restClient;

    private DiscordAuthStrategy discordAuthStrategy;

    @BeforeEach
    void setUp() {
        discordAuthStrategy =
                new DiscordAuthStrategy(
                        "http://localhost:3000",
                        "mock-client-id",
                        "mock-client-secret",
                        restClient);
    }

    @SuppressWarnings("unchecked")
    private void stubTokenExchange(Map<String, Object> tokenResponse) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("https://discord.com/api/oauth2/token")).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        // RequestBodySpec.body(...) 有多個多載（Object / 泛型 T / StreamingHttpOutputMessage.Body），
        // 沒型別提示的 any() 會被編譯器解析到錯的那個多載，要用 any(Object.class) 明確指定
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(tokenResponse);
    }

    @SuppressWarnings("unchecked")
    private void stubUserProfile(Map<String, Object> userProfile) {
        RestClient.RequestHeadersUriSpec getUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getUriSpec);
        when(getUriSpec.uri("https://discord.com/api/users/@me")).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(userProfile);
    }

    // 完整流程走一輪：換到 access_token、撈到用戶資料，且有大頭貼雜湊時要正確拼出 CDN 網址
    @Test
    void verifyAndExtractInfo_returnsUserInfo_withAvatarUrl_whenAvatarHashPresent() {
        stubTokenExchange(Map.of("access_token", "discord-access-token"));
        stubUserProfile(
                Map.of(
                        "id", "discord-uid-123",
                        "email", "user@example.com",
                        "username", "DiscordUser",
                        "avatar", "abc123hash",
                        "verified", true));

        OAuthUserInfoDto result = discordAuthStrategy.verifyAndExtractInfo("auth-code");

        assertThat(result.providerUserId()).isEqualTo("discord-uid-123");
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.username()).isEqualTo("DiscordUser");
        assertThat(result.avatarUrl())
                .isEqualTo("https://cdn.discordapp.com/avatars/discord-uid-123/abc123hash.png");
        assertThat(result.provider()).isEqualTo(AuthProvider.DISCORD);
        assertThat(result.isEmailVerified()).isTrue();
    }

    // 使用者沒有設定大頭貼（avatar 欄位是 null），不該硬拼出一個壞掉的網址，應該直接留空
    @Test
    void verifyAndExtractInfo_returnsNullAvatarUrl_whenAvatarHashMissing() {
        stubTokenExchange(Map.of("access_token", "discord-access-token"));
        stubUserProfile(
                Map.of(
                        "id", "discord-uid-123",
                        "email", "user@example.com",
                        "username", "DiscordUser",
                        "verified", false));

        OAuthUserInfoDto result = discordAuthStrategy.verifyAndExtractInfo("auth-code");

        assertThat(result.avatarUrl()).isNull();
        assertThat(result.isEmailVerified()).isFalse();
    }

    // Discord 沒有回傳 access_token（授權碼無效或過期），應該拒絕並丟出對應例外
    @Test
    void verifyAndExtractInfo_throwsTokenExpiredOrInvalid_whenNoAccessTokenReturned() {
        stubTokenExchange(Map.of("error", "invalid_grant"));

        assertThatThrownBy(() -> discordAuthStrategy.verifyAndExtractInfo("expired-code"))
                .isInstanceOf(AuthException.class);
    }

    // Discord 帳號沒有綁定 Email，Classcord 不接受這種帳號，應該拒絕
    @Test
    void verifyAndExtractInfo_throwsEmailNotFound_whenDiscordAccountHasNoEmail() {
        stubTokenExchange(Map.of("access_token", "discord-access-token"));
        stubUserProfile(
                Map.of(
                        "id", "discord-uid-123",
                        "username", "DiscordUser",
                        "verified", true));

        assertThatThrownBy(() -> discordAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }

    // 換 Token 階段連線異常（例如 Discord 服務中斷），應該包裝成統一的第三方服務異常，而不是讓原始例外洩漏出去
    @Test
    void verifyAndExtractInfo_wrapsAsOAuthProviderError_whenTokenExchangeFails() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenThrow(new RuntimeException("連線逾時"));

        assertThatThrownBy(() -> discordAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }
}
