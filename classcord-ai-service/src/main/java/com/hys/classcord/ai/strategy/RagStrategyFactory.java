package com.hys.classcord.ai.strategy;

import com.hys.classcord.ai.enums.RagStrategyType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@RefreshScope
@Component
public class RagStrategyFactory {

    private final Map<RagStrategyType, RagIndexingStrategy> strategyMap;

    // todo yml
    @Value("${classcord.ai.rag-strategy:DEFAULT}")
    private String configuredStrategy;

    // Spring 自動注入所有實作了 RagIndexingStrategy 的 Bean 並放入 List 中
    public RagStrategyFactory(List<RagIndexingStrategy> strategies) {
        this.strategyMap =
                strategies.stream()
                        .collect(
                                Collectors.toMap(
                                        RagIndexingStrategy::getType, Function.identity()));
    }

    // 根據組態設定取得對應策略
    public RagIndexingStrategy getStrategy() {
        RagStrategyType type;
        try {
            type = RagStrategyType.valueOf(configuredStrategy.toUpperCase());
        } catch (Exception e) {
            type = RagStrategyType.DEFAULT;
        }
        return strategyMap.getOrDefault(type, strategyMap.get(RagStrategyType.DEFAULT));
    }
}
