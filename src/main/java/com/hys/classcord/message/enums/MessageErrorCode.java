package com.hys.classcord.message.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MessageErrorCode {
    MESSAGE_NOT_FOUND("MESSAGE_001", HttpStatus.NOT_FOUND, "找不到該訊息"),
    INSUFFICIENT_PERMISSIONS("MESSAGE_002", HttpStatus.FORBIDDEN, "權限不足，無法操作此訊息"),
    NOT_SERVER_MEMBER("MESSAGE_003", HttpStatus.FORBIDDEN, "您非該伺服器的成員，無法存取頻道訊息"),
    CHANNEL_NOT_FOUND("MESSAGE_004", HttpStatus.NOT_FOUND, "找不到該頻道"),
    ADMIN_CHANNEL_ACCESS_DENIED("MESSAGE_005", HttpStatus.FORBIDDEN, "此為管理員頻道，學生無法存取"),
    MATERIAL_CHANNEL_POST_DENIED("MESSAGE_006", HttpStatus.FORBIDDEN, "此為教材頻道，只有教師或助教能發送訊息");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
