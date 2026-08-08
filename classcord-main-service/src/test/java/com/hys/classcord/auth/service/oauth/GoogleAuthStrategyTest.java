package com.hys.classcord.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hys.classcord.auth.exception.AuthException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

// 純單元測試：不真的打 Google API。
// 注意：GoogleAuthStrategy 內部的 GoogleIdTokenVerifier 是建構子內部 new 出來的，不是注入進來的依賴，
// 沒辦法用 Mockito mock 掉，所以「id_token 驗證成功」這條happy path 沒辦法在不改動正式程式碼的前提下測到。
// 這裡改用一個真實但格式錯誤的字串當 id_token，讓真正的 GoogleIdTokenVerifier 自然解析失敗，
// 藉此驗證「Token 驗證失敗時有沒有被正確包裝成 AuthException」這條錯誤處理邏輯。
@ExtendWith(MockitoExtension.class)
class GoogleAuthStrategyTest {

    @Mock private RestClient restClient;

    private GoogleAuthStrategy googleAuthStrategy;

    @BeforeEach
    void setUp() {
        googleAuthStrategy =
                new GoogleAuthStrategy(
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
}
