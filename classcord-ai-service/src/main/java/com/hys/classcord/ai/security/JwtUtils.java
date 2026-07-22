package com.hys.classcord.ai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtils(
            @Value("${app.jwt.secret-key}") String secretString,
            @Value("${app.jwt.expiration-ms:86400000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secretString.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(UUID userId, String email) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String getUserIdFromToken(String token) {
        try {
            Claims claims =
                    Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            log.debug("JWT Token 已過期: {}", e.getMessage());
            return null;
        } catch (SignatureException e) {
            log.warn("偵測到無效的 JWT 簽章，Token 可能被竄改！原因: {}", e.getMessage());
            return null;
        } catch (MalformedJwtException e) {
            log.warn("偵測到格式錯誤的 JWT Token！原因: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("JWT 解析時發生未預期異常: ", e);
            return null;
        }
    }

    public Date getExpirationDateFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
    }

    public long getRemainingTimeInSeconds(String token) {
        try {
            Date expiration = getExpirationDateFromToken(token);
            long diff = expiration.getTime() - System.currentTimeMillis();

            if (diff > 0) {
                long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(diff);
                long bufferSeconds = 120;
                return remainingSeconds + bufferSeconds;
            }
            return 60;
        } catch (Exception e) {
            return 0;
        }
    }
}
