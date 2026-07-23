package com.hys.classcord.quiz.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum QuizErrorCode {
    QUIZ_NOT_FOUND("QUIZ_001", HttpStatus.NOT_FOUND, "找不到該測驗紀錄"),
    QUESTION_NOT_FOUND("QUIZ_002", HttpStatus.NOT_FOUND, "找不到該考題"),
    INSUFFICIENT_PERMISSIONS("QUIZ_003", HttpStatus.FORBIDDEN, "權限不足，無法進行此操作"),
    INSUFFICIENT_POOL_QUESTIONS(
            "QUIZ_004", HttpStatus.BAD_REQUEST, "此教材的有效題庫少於 10 題，無法建立測驗，請聯繫教師生成題目"),
    QUIZ_ALREADY_SUBMITTED("QUIZ_005", HttpStatus.CONFLICT, "此測驗已經完成作答提交，無法重複提交"),
    POOL_LIMIT_EXCEEDED("QUIZ_006", HttpStatus.CONFLICT, "該教材的有效題庫數量已達上限"),
    MATERIAL_NOT_ENABLED("QUIZ_007", HttpStatus.BAD_REQUEST, "該教材尚未啟用 AI 助教，無法進行出題"),
    NOT_SERVER_MEMBER("QUIZ_008", HttpStatus.FORBIDDEN, "您非該伺服器的成員，無法存取測驗"),
    AI_ANALYSIS_IN_PROGRESS("QUIZ_009", HttpStatus.CONFLICT, "AI 正在分析此教材，請稍後再試"),
    AI_ANALYSIS_RATE_LIMIT("QUIZ_010", HttpStatus.TOO_MANY_REQUESTS, "此教材一天只能進行一次 AI 分析，請明日再試"),
    AI_ANALYSIS_FAILED("QUIZ_011", HttpStatus.INTERNAL_SERVER_ERROR, "AI 分析失敗"),
    AI_ANALYSIS_NOT_FOUND("QUIZ_012", HttpStatus.NOT_FOUND, "此教材尚未進行 AI 分析，請點擊「分析」按鈕開始");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
