package com.hys.classcord.ai.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hys.classcord.ai.config.AiLimitProperties;
import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.ai.exception.AiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

// 純單元測試：不啟動 Spring context、不連真的 Redis。
@ExtendWith(MockitoExtension.class)
class AiRateLimitServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<Long> rateLimitScript;

    private final AiLimitProperties aiLimitProperties = new AiLimitProperties();

    private AiRateLimitService aiRateLimitService;

    @BeforeEach
    void setUp() {
        aiLimitProperties.setChatDailyMax(400);
        aiLimitProperties.setEmbeddingDailyMax(3000);
        aiRateLimitService =
                new AiRateLimitService(redisTemplate, rateLimitScript, aiLimitProperties);
    }

    // 未達上限，應該正常放行不丟例外
    @Test
    void checkChatRateLimit_doesNotThrow_whenUnderLimit() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any(), any())).thenReturn(399L);

        assertThatCode(() -> aiRateLimitService.checkChatRateLimit()).doesNotThrowAnyException();
    }

    // 已達上限，應該拒絕
    @Test
    void checkChatRateLimit_throwsRateLimitExceeded_whenOverLimit() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any(), any())).thenReturn(401L);

        assertThatThrownBy(() -> aiRateLimitService.checkChatRateLimit())
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.RATE_LIMIT_EXCEEDED.getCode());
    }

    // 向量化限流：批次數量未達上限，應該放行
    @Test
    void checkEmbeddingRateLimit_doesNotThrow_whenUnderLimit() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any(), any())).thenReturn(2999L);

        assertThatCode(() -> aiRateLimitService.checkEmbeddingRateLimit(1))
                .doesNotThrowAnyException();
    }

    // 向量化限流：這批切片數量加上去會超過上限，應該拒絕
    @Test
    void checkEmbeddingRateLimit_throwsEmbeddingLimitExceeded_whenOverLimit() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any(), any())).thenReturn(3001L);

        assertThatThrownBy(() -> aiRateLimitService.checkEmbeddingRateLimit(50))
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.EMBEDDING_LIMIT_EXCEEDED.getCode());
    }

    // Redis 本身發生異常（非預期狀況），應該保守拒絕並包裝成 AiException，而不是讓原始例外洩漏出去
    @Test
    void checkChatRateLimit_wrapsAsAiException_whenRedisFails() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any(), any()))
                .thenThrow(new RuntimeException("Redis 連線逾時"));

        assertThatThrownBy(() -> aiRateLimitService.checkChatRateLimit())
                .isInstanceOf(AiException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.RATE_LIMIT_EXCEEDED.getCode());
    }
}
