package com.hys.classcord.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// 純單元測試：不啟動 Spring context，直接呼叫 doFilterInternal（跟 filter 同一個 package，
// protected 方法可以直接呼叫，不需要反射）。用 spring-test 的 MockHttpServletRequest/Response
// 建假的 Servlet 請求，FilterChain 用 Mockito mock。
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtils, redisTemplate);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void doFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
    }

    private void verifyChainCalledOnce(MockHttpServletRequest request) throws Exception {
        verify(filterChain, times(1)).doFilter(eq(request), any(HttpServletResponse.class));
    }

    // Gateway 已經驗證過 JWT 並注入 X-User-Id，這裡應該直接信任它，把使用者身分綁到 SecurityContext
    @Test
    void doFilterInternal_authenticatesFromXUserIdHeader_whenPresentAndValid() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());

        doFilter(request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(userId);
        verifyChainCalledOnce(request);
    }

    // X-User-Id 標頭格式不是合法 UUID（可能被竄改），應該清空認證狀態，而不是讓例外往外拋
    @Test
    void doFilterInternal_clearsContext_whenXUserIdIsNotValidUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "not-a-uuid");

        doFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyChainCalledOnce(request);
    }

    // 就算帶了 X-User-Id，只要同時帶的 Token 已經被列入黑名單（例如已登出），也要清空認證狀態
    @Test
    void doFilterInternal_clearsContext_whenTokenPresentAndBlacklisted_evenWithXUserId()
            throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        request.addHeader("Authorization", "Bearer revoked-token");
        when(redisTemplate.hasKey("JWT_BLACKLIST:revoked-token")).thenReturn(true);

        doFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyChainCalledOnce(request);
    }

    // 沒有 X-User-Id（例如直接呼叫、繞過 gateway），退回自己解析 JWT，成功的話應該正常認證
    @Test
    void doFilterInternal_authenticatesFromJwt_whenXUserIdAbsentButTokenValid() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtUtils.getUserIdFromToken("valid-token")).thenReturn(userId.toString());
        when(redisTemplate.hasKey("JWT_BLACKLIST:valid-token")).thenReturn(false);

        doFilter(request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(userId);
        verifyChainCalledOnce(request);
    }

    // 沒有 X-User-Id，自行解析出的 JWT 已被列入黑名單，應該清空認證狀態
    @Test
    void doFilterInternal_clearsContext_whenNoXUserIdAndTokenBlacklisted() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer revoked-token");
        when(jwtUtils.getUserIdFromToken("revoked-token")).thenReturn(userId.toString());
        when(redisTemplate.hasKey("JWT_BLACKLIST:revoked-token")).thenReturn(true);

        doFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyChainCalledOnce(request);
    }

    // 沒有 X-User-Id，JWT 本身無效或過期（解析不出 userId），應該直接放行但不設定任何認證身分
    @Test
    void doFilterInternal_leavesUnauthenticated_whenTokenInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        when(jwtUtils.getUserIdFromToken("invalid-token")).thenReturn(null);

        doFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyChainCalledOnce(request);
    }

    // 完全沒有 X-User-Id 也沒有 Authorization Header（例如公開端點），應該直接放行
    @Test
    void doFilterInternal_passesThrough_whenNoIdentityInfoProvided() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        doFilter(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyChainCalledOnce(request);
    }
}
