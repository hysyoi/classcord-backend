package com.hys.classcord.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// todo API Gateway <- IP 交由這個防禦
// todo 這個 RateLimitFilter 可以改成針對同一個帳號 (Email)＋IP 做限制，可能還是 CAPTCHA 最好
/** 註冊與登入請求限制 (Redis 分散式限流) */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    // todo 硬編碼
    private static final int MAX_ATTEMPTS = 30; // 最大嘗試次數
    private static final int EXPIRE_SECONDS = 60; // 限制時間（秒）

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript; // Lua 腳本

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 只限制登入和註冊端點
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/auth/")) {
            chain.doFilter(request, response);
            return;
        }

        // 只針對寫入型/敏感端點進行限流，排除 GET 請求（如開通帳號 /activate）
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = getClientIp(request);
        String redisKey = "RATE_LIMIT:IP:" + ip;

        // 執行 Lua 腳本，保證自增與過期在 Redis 端一次完成（原子操作）
        Long count =
                redisTemplate.execute(
                        rateLimitScript, // 直接傳入 Bean，不需要再寫字串
                        Collections.singletonList(redisKey),
                        String.valueOf(EXPIRE_SECONDS));

        if (count != null && count > MAX_ATTEMPTS) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"請求過於頻繁，請稍後再試\"}");
            return; // 攔截
        }

        chain.doFilter(request, response);
    }

    // todo Nginx配置real_ip
    /** 在生產環境中取得使用者真正的IP，而非代理伺服器的 */
    private String getClientIp(HttpServletRequest request) {

        // 1. 先從 X-Forwarded-For 標頭拿 IP
        String ip = request.getHeader("X-Forwarded-For");

        // 2. 如果沒有，再嘗試 X-Real-IP
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }

        // 3. 都沒有才使用 getRemoteAddr() 兜底
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 4. 對於多重代理，X-Forwarded-For 的值可能是 "IP1, IP2, IP3..."，第一個才是真實客戶端 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }
}
