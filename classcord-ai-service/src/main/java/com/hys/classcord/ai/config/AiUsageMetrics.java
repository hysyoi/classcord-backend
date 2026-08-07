package com.hys.classcord.ai.config;

import com.hys.classcord.ai.dto.AiLimitStatusResponse;
import com.hys.classcord.ai.service.AiAssistantService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把 {@link AiAssistantService#getAiLimitStatus()} 現成算好的今日 AI 用量／預估花費， 額外曝露成 Prometheus Gauge，讓
 * Grafana 能畫出歷史趨勢圖。
 *
 * <p>每個 Gauge 若各自即時呼叫 getAiLimitStatus() 會重複打 Redis，這裡改成排程統一刷新一份快照， 所有 Gauge 共用同一份數據，並用
 * AtomicReference 讓刷新執行緒與 Prometheus 抓取執行緒之間的讀寫安全。
 */
@Component
@RequiredArgsConstructor
public class AiUsageMetrics {

    private final AiAssistantService aiAssistantService;
    private final MeterRegistry meterRegistry;

    private final AtomicReference<AiLimitStatusResponse> latest = new AtomicReference<>();

    @PostConstruct
    void registerMetrics() {
        refresh();

        Gauge.builder("ai.usage.chat.calls", latest, ref -> ref.get().chatCount())
                .description("今日全站 AI 對話累計呼叫次數")
                .register(meterRegistry);

        Gauge.builder("ai.usage.embedding.calls", latest, ref -> ref.get().embeddingCount())
                .description("今日全站 AI Embedding 累計呼叫次數")
                .register(meterRegistry);

        Gauge.builder("ai.usage.cost.usd", latest, ref -> ref.get().estimatedCostUsd())
                .description("今日全站 AI 預估花費（美元）")
                .register(meterRegistry);

        Gauge.builder("ai.usage.cost.twd", latest, ref -> ref.get().estimatedCostTwd())
                .description("今日全站 AI 預估花費（新台幣）")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 30_000)
    void refresh() {
        latest.set(aiAssistantService.getAiLimitStatus());
    }
}
