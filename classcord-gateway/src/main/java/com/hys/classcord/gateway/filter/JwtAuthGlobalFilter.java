package com.hys.classcord.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 網關全域 JWT 鑑權過濾器 1. 支援 CORS Preflight (OPTIONS) 自動放行 2. 嚴格防止外部請求防篡改標頭注入 (Stripping
 * untrusted X-User-Id / X-User-Email headers) 3. 記憶體高速簽名驗證與 Claims 標頭注入透傳
 */
@Slf4j
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private final SecretKey key;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // 鑑權白名單清單 (無需攜帶 JWT 即可存取的公開端點)
    private static final List<String> WHITELIST =
            List.of(
                    "/v1/auth/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/webjars/**",
                    "/swagger-resources/**",
                    "/actuator/**",
                    "/ws/**",
                    "/v1/materials/questions/tasks/*/stream",
                    "/error");

    public JwtAuthGlobalFilter(@Value("${app.jwt.secret-key}") String secretString) {
        this.key = Keys.hmacShaKeyFor(secretString.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 最佳實踐：CORS Preflight (OPTIONS 請求) 無條件放行，確保前端跨域無障礙
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        // 2. 檢查白名單：若在白名單內直接放行
        for (String pattern : WHITELIST) {
            if (pathMatcher.match(pattern, path)) {
                return chain.filter(exchange);
            }
        }

        // 3. 提取 Authorization Header 中的 Bearer Token
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("【Gateway 鑑權防禦】請求未攜帶 Bearer Token，拒絕連線: path={}", path);
            return unauthorizedResponse(exchange, "未提供認證令牌，請重新登入");
        }

        String token = authHeader.substring(7);

        // 4. 高速驗證 JWT Token 簽名與過期時間
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            log.warn("【Gateway 鑑權防禦】無效或過期的 JWT Token: path={}, error={}", path, e.getMessage());
            return unauthorizedResponse(exchange, "認證失效，請重新登入");
        }

        String userId = claims.getSubject();
        String email = claims.get("email", String.class);

        // 5. 生產級安全最佳實踐：先抹除外網客戶端可能偽造的 X-User-Id 標頭，再注入驗證通過的合法身份
        ServerHttpRequest mutatedRequest =
                request.mutate()
                        .headers(
                                httpHeaders -> {
                                    httpHeaders.remove("X-User-Id");
                                    httpHeaders.remove("X-User-Email");
                                })
                        .header("X-User-Id", userId != null ? userId : "")
                        .header("X-User-Email", email != null ? email : "")
                        .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"code\":\"AUTH_002\",\"message\":\"%s\"}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // 高優先級執行
    }
}
