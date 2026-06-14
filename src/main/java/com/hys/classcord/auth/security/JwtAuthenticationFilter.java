package com.hys.classcord.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;

    // todo 考慮新增jwt_version
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = parseJwt(request);

        if (token != null) {

            // 1. 嘗試解析並取得 UserId (底層只會進行一次 JWT 解析與簽章驗證)
            String userId = jwtUtils.getUserIdFromToken(token);

            // 2. 如果解析成功，代表 Token 合法且未過期
            if (userId != null) {
                // 3. 檢查 Redis 黑名單
                String redisKey = "JWT_BLACKLIST:" + token;
                Boolean isBlacklisted = redisTemplate.hasKey(redisKey);

                if (Boolean.TRUE.equals(isBlacklisted)) {
                    // 如果在黑名單內，無情作廢！
                    // 確保當前 Context 是乾淨的（沒有認證資訊）
                    SecurityContextHolder.clearContext();
                    // 直接放行讓它往後走，後面的 Spring Security 門神看到他沒認證資訊，自然會把他彈飛 (401)
                    filterChain.doFilter(request, response);
                    return;
                }

                // 4. 進行 Null 安全的 UUID 轉換與認證資訊注入
                try {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    UUID.fromString(userId), null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (IllegalArgumentException e) {
                    log.warn("JWT 中的 sub 欄位不是有效的 UUID 格式: {}", userId);
                    SecurityContextHolder.clearContext();
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    // @Override
    // protected void doFilterInternal(
    //         HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
    //         throws ServletException, IOException {
    //
    //     String token = parseJwt(request);
    //
    //     // 第一關：純數學與時間驗證 (車票本身是不是真的)
    //     if (token != null && jwtUtils.validateToken(token)) {
    //
    //         // 第二關：檢查 Redis 黑名單 (看這張真車票有沒有被掛失作廢)
    //         String redisKey = "JWT_BLACKLIST:" + token;
    //         Boolean isBlacklisted = redisTemplate.hasKey(redisKey);
    //
    //         if (Boolean.TRUE.equals(isBlacklisted)) {
    //             // 如果在黑名單內，無情作廢！
    //             // 確保當前 Context 是乾淨的（沒有認證資訊）
    //             SecurityContextHolder.clearContext();
    //             // 直接放行讓它往後走，後面的 Spring Security 門神看到他沒認證資訊，自然會把他彈飛 (401)
    //             filterChain.doFilter(request, response);
    //             return;
    //         }
    //
    //         // --- 通過兩道防線，成功放行並注入認證資訊 ---
    //         String userId = jwtUtils.getUserIdFromToken(token);
    //
    //         UsernamePasswordAuthenticationToken authentication =
    //                 new UsernamePasswordAuthenticationToken(
    //                         UUID.fromString(userId), null, Collections.emptyList());
    //
    //         SecurityContextHolder.getContext().setAuthentication(authentication);
    //     }
    //
    //     filterChain.doFilter(request, response);
    // }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}
