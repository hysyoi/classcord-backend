package com.hys.classcord.auth.service;

import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.exception.AuthException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationTokenService {

    private final StringRedisTemplate redisTemplate;

    // SecureRandom (安全隨機數產生器)
    private final SecureRandom secureRandom;

    /** 建立萬用 Token 並存入 Redis */
    public String createToken(String purpose, String value, long timeoutInMinutes) {
        // 1. 產生 32 bytes (256 bits) 的強安全隨機數
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        // 2. 轉換為 URL 安全且沒有填充號 (=) 的 Base64 字串
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String redisKey = "AUTH:" + purpose + ":" + token;
        redisTemplate.opsForValue().set(redisKey, value, timeoutInMinutes, TimeUnit.MINUTES);
        return token;
    }

    /** 驗證並取出 Value，用完立刻單次銷毀 */
    public String verifyAndConsume(String purpose, String token) {
        String redisKey = "AUTH:" + purpose + ":" + token;

        // 保證原子性
        String value = redisTemplate.opsForValue().getAndDelete(redisKey);

        if (value == null) {
            // 這裡可以丟出自訂的 Token 過期或無效異常
            throw new AuthException(AuthErrorCode.TOKEN_EXPIRED_OR_INVALID);
        }

        return value;
    }
}
