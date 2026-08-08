package com.hys.classcord.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.exception.AuthException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class GoogleAuthStrategyTest {

    @Mock private RestClient restClient;
    @Mock private GoogleIdTokenVerifier verifier;

    private GoogleAuthStrategy googleAuthStrategy;

    @BeforeEach
    void setUp() {
        googleAuthStrategy =
                new GoogleAuthStrategy(
                        verifier,
                        "mock-client-id",
                        "mock-client-secret",
                        "http://localhost:3000",
                        restClient);
    }

    @SuppressWarnings("unchecked")
    private void stubTokenExchange(Map<String, Object> tokenResponse) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("https://oauth2.googleapis.com/token")).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(tokenResponse);
    }

    // Google 沒有回傳 id_token（換 Token 階段就失敗），應該拒絕
    @Test
    void verifyAndExtractInfo_throwsOAuthProviderError_whenNoIdTokenReturned() {
        stubTokenExchange(Map.of("access_token", "some-access-token")); // 故意不含 id_token

        assertThatThrownBy(() -> googleAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }

    // 換 Token 階段連線異常，應該包裝成統一的第三方服務異常
    @Test
    void verifyAndExtractInfo_wrapsAsOAuthProviderError_whenTokenExchangeFails() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenThrow(new RuntimeException("連線逾時"));

        assertThatThrownBy(() -> googleAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }

    // 拿到 id_token 字串，但內容不是合法的 Google 簽發 JWT（例如遭竄改或格式錯誤），
    // 驗證應該失敗並被包裝成 AuthException，而不是讓底層解析例外直接洩漏出去
    @Test
    void verifyAndExtractInfo_throwsTokenExpiredOrInvalid_whenIdTokenIsMalformed() {
        stubTokenExchange(Map.of("id_token", "this-is-not-a-valid-jwt"));

        assertThatThrownBy(() -> googleAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }

    // id_token 驗證通過時，應該正確從 Payload 解析出使用者資訊
    @Test
    void verifyAndExtractInfo_returnsUserInfo_whenIdTokenIsValid() throws Exception {
        stubTokenExchange(Map.of("id_token", "valid-id-token"));

        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-user-123");
        payload.setEmail("student@gmail.com");
        payload.setEmailVerified(true);
        payload.set("name", "Test Student");
        payload.set("picture", "https://example.com/avatar.png");

        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(payload);
        when(verifier.verify("valid-id-token")).thenReturn(idToken);

        OAuthUserInfoDto result = googleAuthStrategy.verifyAndExtractInfo("auth-code");

        assertThat(result.providerUserId()).isEqualTo("google-user-123");
        assertThat(result.email()).isEqualTo("student@gmail.com");
        assertThat(result.username()).isEqualTo("Test Student");
        assertThat(result.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(result.isEmailVerified()).isTrue();
    }
}
