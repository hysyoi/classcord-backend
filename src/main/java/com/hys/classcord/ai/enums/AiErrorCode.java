package com.hys.classcord.ai.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode {
    RATE_LIMIT_EXCEEDED("AI_001", HttpStatus.TOO_MANY_REQUESTS, "全站今日 AI 對話呼叫次數已達上限，請稍後再試。"),
    EMBEDDING_LIMIT_EXCEEDED("AI_002", HttpStatus.TOO_MANY_REQUESTS, "全站今日 AI 向量化額度已達上限。");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
