package com.hys.classcord.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 調用限制狀態與額度預估回傳")
public record AiLimitStatusResponse(
        @Schema(description = "目前日期") String date,
        @Schema(description = "今日對話累計次數") long chatCount,
        @Schema(description = "對話每日上限次數") int chatMax,
        @Schema(description = "對話額度消耗進度 (百分比)") double chatProgress,
        @Schema(description = "今日向量化累計次數") long embeddingCount,
        @Schema(description = "向量化每日上限次數") int embeddingMax,
        @Schema(description = "向量化額度消耗進度 (百分比)") double embeddingProgress,
        @Schema(description = "今日累計預估成本 (美金 USD)") double estimatedCostUsd,
        @Schema(description = "今日累計預估成本 (台幣 TWD)") double estimatedCostTwd) {}
