package com.hys.classcord.ai.config;

import com.hys.classcord.ai.dto.AiLimitStatusResponse;
import com.hys.classcord.ai.service.AiAssistantService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把 {@link AiAssistantService#getAiLimitStatus()} 現成算好的今日 AI 用量／預估花費， 額外曝露成 Prometheus Gauge，讓
 * Grafana 能畫出歷史趨勢圖。
 *
 * <p>每個 Gauge 若各自即時呼叫 getAiLimitStatus() 會重複打 Redis，這裡改成排程統一刷新一份快照， 所有 Gauge 共用同一份數據，並用
 * AtomicReference 讓刷新執行緒與 Prometheus 抓取執行緒之間的讀寫安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageMetrics {

    private final AiAssistantService aiAssistantService;
    private final MeterRegistry meterRegistry;

    private final AtomicReference<AiLimitStatusResponse> latest = new AtomicReference<>();

    @PostConstruct
    void registerMetrics() {
        // 啟動當下若 Redis 還沒就緒導致這裡拋例外，會讓整個 Bean 初始化失敗、拖垮服務啟動，
        // 所以第一次刷新失敗只記錄警告、放行讓服務照常啟動，latest 保持 null，
        // 由下方 Gauge 的空值防呆頂著，等下一次排程刷新自然補上真實數據
        refresh();

        Gauge.builder("ai.usage.chat.calls", latest, ref -> valueOrZero(ref, s -> s.chatCount()))
                .description("今日全站 AI 對話累計呼叫次數")
                .register(meterRegistry);

        Gauge.builder(
                        "ai.usage.embedding.calls",
                        latest,
                        ref -> valueOrZero(ref, s -> s.embeddingCount()))
                .description("今日全站 AI Embedding 累計呼叫次數")
                .register(meterRegistry);

        Gauge.builder(
                        "ai.usage.cost.usd",
                        latest,
                        ref -> valueOrZero(ref, s -> s.estimatedCostUsd()))
                .description("今日全站 AI 預估花費（美元）")
                .register(meterRegistry);

        Gauge.builder(
                        "ai.usage.cost.twd",
                        latest,
                        ref -> valueOrZero(ref, s -> s.estimatedCostTwd()))
                .description("今日全站 AI 預估花費（新台幣）")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 30_000)
    void refresh() {
        try {
            latest.set(aiAssistantService.getAiLimitStatus());
        } catch (Exception e) {
            log.warn("刷新 AI 使用量指標失敗，維持上一次的數據，等下次排程重試", e);
        }
    }

    private double valueOrZero(
            AtomicReference<AiLimitStatusResponse> ref,
            ToDoubleFunction<AiLimitStatusResponse> extractor) {
        AiLimitStatusResponse data = ref.get();
        return data == null ? 0 : extractor.applyAsDouble(data);
    }
}
