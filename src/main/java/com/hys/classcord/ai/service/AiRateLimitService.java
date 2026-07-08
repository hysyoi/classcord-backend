package com.hys.classcord.ai.service;

import com.hys.classcord.ai.config.AiLimitProperties;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final AiLimitProperties aiLimitProperties;

    /** 檢查對話（Chat）限流額度 */
    public void checkChatRateLimit() {
        String key = "ai:all-site:limit:" + LocalDate.now();
        checkRateLimit(
                key,
                aiLimitProperties.getChatDailyMax(),
                AiErrorCode.RATE_LIMIT_EXCEEDED,
                "安全檢測系統異常，請稍後再試。");
    }

    /** 檢查向量嵌入（Embedding）限流額度 */
    public void checkEmbeddingRateLimit() {
        String key = "ai:all-site:embedding-limit:" + LocalDate.now();
        checkRateLimit(
                key,
                aiLimitProperties.getEmbeddingDailyMax(),
                AiErrorCode.EMBEDDING_LIMIT_EXCEEDED,
                "安全系統異常，暫時無法執行向量化");
    }

    private void checkRateLimit(
            String key, int maxLimit, AiErrorCode errorCode, String systemErrorMessage) {
        try {
            Long count =
                    redisTemplate.execute(
                            rateLimitScript,
                            Collections.singletonList(key),
                            String.valueOf(getSecondsUntilMidnight()));
            long currentCount = Optional.ofNullable(count).orElse(0L);

            if (currentCount > maxLimit) {
                log.error("【全站限流】已觸發全站 AI 呼叫次數限制！Key={}, Limit={}", key, maxLimit);
                throw new AiException(errorCode);
            }
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("【安全防護警告】限流檢測過程中 Redis 異常，錯誤: {}", e.getMessage(), e);
            throw new AiException(errorCode, systemErrorMessage);
        }
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = LocalDate.now().plusDays(1).atStartOfDay();
        // 加上 10 分鐘（600 秒）的安全緩衝時間，防範伺服器與 Redis 時鐘漂移或跨日邊界併發問題
        return Math.max(1, Duration.between(now, midnight).toSeconds() + 600);
    }
}
