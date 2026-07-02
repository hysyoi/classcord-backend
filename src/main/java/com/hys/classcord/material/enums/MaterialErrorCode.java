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
    UPLOAD_FREQUENCY_LIMIT_EXCEEDED("MATERIAL_009", HttpStatus.TOO_MANY_REQUESTS, "上傳過於頻繁，請稍後再試"),
    AI_ASSISTANT_PROCESSING("MATERIAL_010", HttpStatus.CONFLICT, "該教材 AI 助教正在啟用處理中，請勿重複發送"),
    AI_ASSISTANT_ALREADY_ENABLED("MATERIAL_011", HttpStatus.BAD_REQUEST, "該教材 AI 助教已經啟用完成，可直接進行問答"),
    UPLOAD_NOT_COMPLETED(
            "MATERIAL_012", HttpStatus.UNPROCESSABLE_ENTITY, "找不到已上傳的檔案，請確認檔案已上傳完成後再確認發布"),
    UPLOAD_SIZE_MISMATCH(
            "MATERIAL_013", HttpStatus.PAYLOAD_TOO_LARGE, "實際上傳大小超過申請大小，疑似惡意上傳，已拒絕並刪除該檔案");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
