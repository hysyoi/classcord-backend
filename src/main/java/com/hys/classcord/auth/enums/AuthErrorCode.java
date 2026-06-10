package com.hys.classcord.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode {

    // 自訂錯誤碼 + HTTP 狀態碼 + 預設英文/中文訊息
    EMAIL_ALREADY_EXISTS("AUTH_001", HttpStatus.CONFLICT, "該 Email 已被註冊"),
    INVALID_CREDENTIALS("AUTH_002", HttpStatus.UNAUTHORIZED, "帳號或密碼錯誤"),
    LOCAL_IDENTITY_NOT_FOUND("AUTH_003", HttpStatus.BAD_REQUEST, "該帳號未設定本地密碼，請使用第三方登入或重設密碼"),
    TOO_MANY_REQUESTS("AUTH_004", HttpStatus.TOO_MANY_REQUESTS, "請求次數過多"),
    TOKEN_EXPIRED_OR_INVALID("AUTH_005", HttpStatus.BAD_REQUEST, "憑證過期或無效");

    private final String code; // 給前端或記 Log 用的自訂業務碼
    private final HttpStatus status;
    private final String message;
}
