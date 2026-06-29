package com.hys.classcord.material.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MaterialErrorCode {
    MATERIAL_NOT_FOUND("MATERIAL_001", HttpStatus.NOT_FOUND, "找不到該教材"),
    INSUFFICIENT_PERMISSIONS("MATERIAL_002", HttpStatus.FORBIDDEN, "權限不足，無法操作此教材"),
    NOT_SERVER_MEMBER("MATERIAL_003", HttpStatus.FORBIDDEN, "您非該伺服器的成員，無法存取教材"),
    SYSTEM_STORAGE_LIMIT_EXCEEDED("MATERIAL_004", HttpStatus.PAYLOAD_TOO_LARGE, "系統總儲存空間已滿，無法上傳"),
    SERVER_STORAGE_LIMIT_EXCEEDED(
            "MATERIAL_005", HttpStatus.PAYLOAD_TOO_LARGE, "此班級的儲存空間已達上限，無法上傳"),
    CHANNEL_NOT_FOUND("MATERIAL_006", HttpStatus.NOT_FOUND, "找不到該頻道"),
    INVALID_CHANNEL_TYPE("MATERIAL_007", HttpStatus.BAD_REQUEST, "該頻道不是教材頻道，無法發布教材"),
    USER_NOT_FOUND("MATERIAL_008", HttpStatus.NOT_FOUND, "找不到該使用者"),
    UPLOAD_FREQUENCY_LIMIT_EXCEEDED("MATERIAL_009", HttpStatus.TOO_MANY_REQUESTS, "上傳過於頻繁，請稍後再試");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
