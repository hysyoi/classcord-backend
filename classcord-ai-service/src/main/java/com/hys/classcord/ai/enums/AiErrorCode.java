package com.hys.classcord.ai.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode {
    RATE_LIMIT_EXCEEDED("AI_001", HttpStatus.TOO_MANY_REQUESTS, "全站今日 AI 對話呼叫次數已達上限，請稍後再試。"),
    EMBEDDING_LIMIT_EXCEEDED("AI_002", HttpStatus.TOO_MANY_REQUESTS, "全站今日 AI 向量化額度已達上限。"),
    SESSION_NOT_FOUND("AI_003", HttpStatus.NOT_FOUND, "對話會話不存在"),
    SESSION_ACCESS_DENIED("AI_004", HttpStatus.FORBIDDEN, "您無權存取此對話紀錄"),
    AI_ASSISTANT_PROCESSING("AI_005", HttpStatus.BAD_REQUEST, "該教材尚未完成 AI 助教啟用，無法建立對話"),
    AI_ASSISTANT_ALREADY_ENABLED("AI_006", HttpStatus.BAD_REQUEST, "該教材已啟用 AI 助教");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
