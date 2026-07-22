package com.hys.classcord.auth.security;

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

// todo 考慮雙token
/** JWT處理器 */
@Slf4j
@Component
public class JwtUtils {

    private final SecretKey key;
    // Todo 這也放進yml
    private final long expirationMs;

    // 使用建構子注入，Spring 會先去 yml 撈出值，才執行這裡面的程式碼
    public JwtUtils(
            @Value("${app.jwt.secret-key}") String secretString,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secretString.getBytes(StandardCharsets.UTF_8)); // 明確指定字符集
        // Token 有效期：設為 1 天 (計算出 1 天的毫秒數)
        this.expirationMs = expirationMs;
    }

    /** 根據使用者 ID 簽發 JWT Token */
    public String generateToken(UUID userId, String email) {
        return Jwts.builder()
                .subject(userId.toString()) // sub: 塞入使用者的主鍵 UUID (該用戶在系統中的唯一識別碼)
                .claim("email", email) // 自定義 Claim: 把 email 也打包進去 (不需要去查資料庫, 無狀態與自包含)
                .issuedAt(new Date()) // iat: 簽發時間
                .expiration(new Date(System.currentTimeMillis() + expirationMs)) // exp: 到期時間
                .signWith(key) // 用那把密鑰匙進行「數位簽章」
                .compact(); // 壓縮成一串以點（.）分隔的 Base64 字串
    }

    /** 從 JWT Token 中解析出 使用者 ID */
    public String getUserIdFromToken(String token) {
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(key) // 拿出密鑰準備解密驗證
                            .build()
                            .parseSignedClaims(token) // 檢查簽名是否被篡改，並解開密文
                            .getPayload(); // 拿到裡面的資料包 (Claims)
            return claims.getSubject(); // 取出 sub 裡面的 User UUID 字串
        } catch (ExpiredJwtException e) {
            log.debug(
                    "JWT Token 已過期: {}", e.getMessage()); // 過期是常態，用 debug 記錄即可，避免塞滿 production 正常日誌
            return null;
        } catch (SignatureException e) {
            log.warn("偵測到無效的 JWT 簽章，Token 可能被竄改！原因: {}", e.getMessage()); // 竄改是安全事件，用 warn
            // 可能可以實作封鎖 IP 等等
            return null;
        } catch (MalformedJwtException e) {
            log.warn("偵測到格式錯誤的 JWT Token！原因: {}", e.getMessage()); // 格式錯誤也可能是探測，用 warn
            // 可能可以實作封鎖 IP 等等
            return null;
        } catch (Exception e) {
            log.error("JWT 解析時發生未預期異常: ", e); // 其他未預期異常，用 error
            return null;
        }
    }

    //
    // /** 驗證 Token 是否合法與過期 */
    // public boolean validateToken(String token) {
    //     try {
    //         Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    //         return true;
    //     } catch (Exception e) {
    //         return false;
    //     }
    // }

    //  登出支援方法

    /** * 從 Token 中獲取過期時間 (exp) */
    public Date getExpirationDateFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration(); // 取得當初發行時設定的過期時間點
    }

    /** 計算 Token 剩餘的有效時間（秒），並自動加上業界安全的防護緩衝時間（例如 2 分鐘） */
    public long getRemainingTimeInSeconds(String token) {
        try {
            Date expiration = getExpirationDateFromToken(token);
            long diff = expiration.getTime() - System.currentTimeMillis();

            if (diff > 0) {
                long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(diff);

                // 額外加上 120 秒（2 分鐘）的緩衝時間，防止伺服器時間差漏洞
                long bufferSeconds = 120;

                return remainingSeconds + bufferSeconds;
            }

            // 如果 Token 本身已經在 Java 端判定過期了，但為了防止極端的時鐘回撥（Clock Rollback）
            // 我們依然可以回傳一個基礎的緩衝時間（例如 60 秒）
            return 60;
        } catch (Exception e) {
            return 0;
        }
    }
}
