package com.hys.classcord.ai.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hys.classcord.ai.enums.RagStrategyType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RagStrategyFactoryTest {

    private RagIndexingStrategy strategyOf(RagStrategyType type) {
        RagIndexingStrategy strategy = mock(RagIndexingStrategy.class);
        when(strategy.getType()).thenReturn(type);
        return strategy;
    }

    // 設定值對應到已註冊的策略時，應該回傳該策略
    @Test
    void getStrategy_returnsConfiguredStrategy_whenTypeIsRegistered() {
        RagIndexingStrategy defaultStrategy = strategyOf(RagStrategyType.DEFAULT);
        RagIndexingStrategy contextualStrategy = strategyOf(RagStrategyType.CONTEXTUAL);
        RagStrategyFactory factory =
                new RagStrategyFactory(List.of(defaultStrategy, contextualStrategy));
        ReflectionTestUtils.setField(factory, "configuredStrategy", "CONTEXTUAL");

        assertThat(factory.getStrategy()).isSameAs(contextualStrategy);
    }

    // 設定字串是合法列舉值，但沒有對應的策略實作被註冊時，應該安全退回 DEFAULT 策略
    @Test
    void getStrategy_fallsBackToDefault_whenConfiguredTypeNotRegistered() {
        RagIndexingStrategy defaultStrategy = strategyOf(RagStrategyType.DEFAULT);
        RagStrategyFactory factory = new RagStrategyFactory(List.of(defaultStrategy));
        ReflectionTestUtils.setField(factory, "configuredStrategy", "LATE_CHUNKING");

        assertThat(factory.getStrategy()).isSameAs(defaultStrategy);
    }

    // 設定字串根本不是合法的列舉值時，也要安全退回 DEFAULT，而不是拋例外炸掉整個消費流程
    @Test
    void getStrategy_fallsBackToDefault_whenConfiguredValueIsInvalid() {
        RagIndexingStrategy defaultStrategy = strategyOf(RagStrategyType.DEFAULT);
        RagStrategyFactory factory = new RagStrategyFactory(List.of(defaultStrategy));
        ReflectionTestUtils.setField(factory, "configuredStrategy", "not-a-real-strategy");

        assertThat(factory.getStrategy()).isSameAs(defaultStrategy);
    }

    // 設定值大小寫不同也要能正確比對到列舉（呼叫端會先 toUpperCase() 再轉換）
    @Test
    void getStrategy_isCaseInsensitive() {
        RagIndexingStrategy contextualStrategy = strategyOf(RagStrategyType.CONTEXTUAL);
        RagStrategyFactory factory = new RagStrategyFactory(List.of(contextualStrategy));
        ReflectionTestUtils.setField(factory, "configuredStrategy", "contextual");

        assertThat(factory.getStrategy()).isSameAs(contextualStrategy);
    }
}
