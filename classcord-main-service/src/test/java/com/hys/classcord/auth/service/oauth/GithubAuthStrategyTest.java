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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

// 純單元測試：不真的打 GitHub API，用 Mockito 假扮 RestClient 的整條 fluent 呼叫鏈。
@ExtendWith(MockitoExtension.class)
class GithubAuthStrategyTest {

    @Mock private RestClient restClient;

    private GithubAuthStrategy githubAuthStrategy;

    @BeforeEach
    void setUp() {
        githubAuthStrategy =
                new GithubAuthStrategy("mock-client-id", "mock-client-secret", restClient);
    }

    @SuppressWarnings("unchecked")
    private void stubTokenExchange(Map<String, Object> tokenResponse) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("https://github.com/login/oauth/access_token")).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.accept(any())).thenReturn(bodySpec);
        // RequestBodySpec.body(...) 有多個多載，沒型別提示的 any() 會被編譯器解析到錯的那個多載，
        // 要用 any(Object.class) 明確指定，才不會 mock 到跟正式程式碼不同的方法（踩過一次同類型的坑）
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(tokenResponse);
    }

    // 讓同一個 getUriSpec 依 URL 分派到不同的回應，因為 GitHub 流程裡可能要打兩個不同的 GET 端點
    // （主要 profile + 備援的私密信箱），而 restClient.get() 本身不吃參數、無法直接依網址區分。
    @SuppressWarnings("unchecked")
    private void stubGet(RestClient.RequestHeadersUriSpec getUriSpec, String uri, Object body) {
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(getUriSpec.uri(uri)).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(body);
    }

    private RestClient.RequestHeadersUriSpec stubGetUriSpec() {
        RestClient.RequestHeadersUriSpec getUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(getUriSpec);
        return getUriSpec;
    }

    // 主要 profile 端點就直接帶有 Email，不需要再去撈私密信箱
    @Test
    void verifyAndExtractInfo_returnsUserInfo_whenEmailPresentInMainProfile() {
        stubTokenExchange(Map.of("access_token", "gh-access-token"));
        RestClient.RequestHeadersUriSpec getUriSpec = stubGetUriSpec();
        stubGet(
                getUriSpec,
                "https://api.github.com/user",
                Map.of(
                        "id", 12345,
                        "email", "user@example.com",
                        "login", "githubuser",
                        "avatar_url", "https://avatars.githubusercontent.com/u/12345"));

        OAuthUserInfoDto result = githubAuthStrategy.verifyAndExtractInfo("auth-code");

        assertThat(result.providerUserId()).isEqualTo("12345");
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.username()).isEqualTo("githubuser");
        assertThat(result.provider()).isEqualTo(AuthProvider.GITHUB);
        assertThat(result.isEmailVerified()).isTrue();
    }

    // 主要 profile 沒有公開 Email 時，應該降級去撈私密信箱清單，挑出「主要且已驗證」的那一筆
    @Test
    void verifyAndExtractInfo_fallsBackToPrivateEmails_whenMainProfileHasNoEmail() {
        stubTokenExchange(Map.of("access_token", "gh-access-token"));
        RestClient.RequestHeadersUriSpec getUriSpec = stubGetUriSpec();
        stubGet(
                getUriSpec,
                "https://api.github.com/user",
                Map.of("id", 12345, "login", "githubuser"));
        stubGet(
                getUriSpec,
                "https://api.github.com/user/emails",
                List.of(
                        Map.of(
                                "email", "secondary@example.com",
                                "primary", false,
                                "verified", true),
                        Map.of(
                                "email", "primary@example.com",
                                "primary", true,
                                "verified", true)));

        OAuthUserInfoDto result = githubAuthStrategy.verifyAndExtractInfo("auth-code");

        assertThat(result.email()).isEqualTo("primary@example.com");
    }

    // 主要 profile 沒有 Email，私密信箱清單裡也找不到「主要且已驗證」的 Email，應該徹底拒絕
    @Test
    void verifyAndExtractInfo_throwsEmailNotFound_whenNoVerifiedPrimaryEmailAnywhere() {
        stubTokenExchange(Map.of("access_token", "gh-access-token"));
        RestClient.RequestHeadersUriSpec getUriSpec = stubGetUriSpec();
        stubGet(
                getUriSpec,
                "https://api.github.com/user",
                Map.of("id", 12345, "login", "githubuser"));
        stubGet(
                getUriSpec,
                "https://api.github.com/user/emails",
                List.of(
                        Map.of(
                                "email",
                                "unverified@example.com",
                                "primary",
                                true,
                                "verified",
                                false)));

        assertThatThrownBy(() -> githubAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }

    // GitHub 沒有回傳 access_token（授權碼無效或過期），應該拒絕
    @Test
    void verifyAndExtractInfo_throwsTokenExpiredOrInvalid_whenNoAccessTokenReturned() {
        stubTokenExchange(Map.of("error", "bad_verification_code"));

        assertThatThrownBy(() -> githubAuthStrategy.verifyAndExtractInfo("expired-code"))
                .isInstanceOf(AuthException.class);
    }

    // 撈取私密信箱清單時連線異常，應該包裝成統一的第三方服務異常
    @Test
    void verifyAndExtractInfo_wrapsAsOAuthProviderError_whenFetchingPrivateEmailsFails() {
        stubTokenExchange(Map.of("access_token", "gh-access-token"));
        RestClient.RequestHeadersUriSpec getUriSpec = stubGetUriSpec();
        stubGet(
                getUriSpec,
                "https://api.github.com/user",
                Map.of("id", 12345, "login", "githubuser"));
        when(getUriSpec.uri("https://api.github.com/user/emails"))
                .thenThrow(new RuntimeException("連線逾時"));

        assertThatThrownBy(() -> githubAuthStrategy.verifyAndExtractInfo("auth-code"))
                .isInstanceOf(AuthException.class);
    }
}
