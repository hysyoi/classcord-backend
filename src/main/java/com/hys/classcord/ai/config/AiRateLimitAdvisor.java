package com.hys.classcord.ai.config;

import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

@Slf4j
public class AiRateLimitAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final int chatDailyMax;

    public AiRateLimitAdvisor(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> rateLimitScript,
            int chatDailyMax) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.chatDailyMax = chatDailyMax;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        checkRateLimit();
        return chain.nextAroundCall(advisedRequest);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(
            AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        checkRateLimit();
        return chain.nextAroundStream(advisedRequest);
    }

    private void checkRateLimit() {
        String key = "ai:all-site:limit:" + LocalDate.now();
        try {
            Long count =
                    redisTemplate.execute(
                            rateLimitScript,
                            Collections.singletonList(key),
                            String.valueOf(getSecondsUntilMidnight()));
            long currentCount = Optional.ofNullable(count).orElse(0L);

            if (currentCount > chatDailyMax) {
                log.error("【全站限流】已觸發全站 AI 呼叫次數限制！");
                throw new AiException(AiErrorCode.RATE_LIMIT_EXCEEDED);
            }
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("【安全防護警告】限流檢測過程中 Redis 異常，Fail-Fast 啟動！錯誤: {}", e.getMessage());
            throw new AiException(AiErrorCode.RATE_LIMIT_EXCEEDED, "安全檢測系統異常，請稍後再試。");
        }
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = LocalDate.now().plusDays(1).atStartOfDay();
        // 加上 10 分鐘（600 秒）的安全緩衝時間，防範伺服器與 Redis 時鐘漂移或跨日邊界併發問題
        return Math.max(1, Duration.between(now, midnight).toSeconds() + 600);
    }

    @Override
    public String getName() {
        return "AiRateLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
