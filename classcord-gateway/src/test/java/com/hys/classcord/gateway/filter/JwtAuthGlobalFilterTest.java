package com.hys.classcord.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

// 純單元測試：不啟動 Spring context、不真的路由請求，直接對這個 GlobalFilter 本身送假的
// ServerWebExchange 進去，驗證鑑權判斷邏輯。
class JwtAuthGlobalFilterTest {

    private static final String SECRET =
            "mock_secret_key_which_must_be_at_least_32_bytes_long_for_hs256_algorithm";

    private JwtAuthGlobalFilter jwtAuthGlobalFilter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        jwtAuthGlobalFilter = new JwtAuthGlobalFilter(SECRET);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private String validToken(String userId, String email) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
                .signWith(key)
                .compact();
    }

    // OPTIONS (CORS Preflight) 應該無條件放行，不需要 Token
    @Test
    void filter_allowsOptionsRequest_withoutToken() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.options("/v1/servers").build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    // 白名單路徑（例如 /v1/auth/**）不需要帶 Token 就能放行
    @Test
    void filter_allowsWhitelistedPath_withoutToken() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/v1/auth/login").build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    // 非白名單路徑完全沒帶 Authorization Header，應該直接拒絕，回傳 401
    @Test
    void filter_rejectsRequest_whenAuthorizationHeaderMissing() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/v1/servers").build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    // Authorization Header 存在但不是「Bearer 」開頭，一樣要拒絕
    @Test
    void filter_rejectsRequest_whenAuthorizationHeaderIsNotBearerFormat() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/servers")
                                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                                .build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    // Token 簽章不合法（竄改或格式錯誤），應該拒絕
    @Test
    void filter_rejectsRequest_whenTokenIsInvalid() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/servers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt")
                                .build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    // Token 合法，應該放行並把驗證通過的使用者身分注入到轉發的請求標頭
    @Test
    void filter_forwardsRequest_withVerifiedUserHeaders_whenTokenIsValid() {
        String token = validToken("user-123", "user@example.com");
        ServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/servers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain, times(1)).filter(captor.capture());
        HttpHeaders forwardedHeaders = captor.getValue().getRequest().getHeaders();
        assertThat(forwardedHeaders.getFirst("X-User-Id")).isEqualTo("user-123");
        assertThat(forwardedHeaders.getFirst("X-User-Email")).isEqualTo("user@example.com");
    }

    // 資安核心防線：就算外部請求自己偽造了 X-User-Id/X-User-Email 標頭，
    // 轉發出去的請求也應該是驗證過的真實身分，偽造的值必須被覆蓋掉，不能矇混過關
    @Test
    void filter_stripsClientSuppliedIdentityHeaders_beforeInjectingVerifiedOnes() {
        String token = validToken("real-user-id", "real@example.com");
        ServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/v1/servers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .header("X-User-Id", "spoofed-admin-id")
                                .header("X-User-Email", "attacker@evil.com")
                                .build());

        StepVerifier.create(jwtAuthGlobalFilter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        HttpHeaders forwardedHeaders = captor.getValue().getRequest().getHeaders();
        assertThat(forwardedHeaders.getFirst("X-User-Id")).isEqualTo("real-user-id");
        assertThat(forwardedHeaders.getFirst("X-User-Email")).isEqualTo("real@example.com");
    }
}
