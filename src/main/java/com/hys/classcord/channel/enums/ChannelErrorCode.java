package com.hys.classcord.channel.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChannelErrorCode {
    CHANNEL_NOT_FOUND("CHANNEL_001", HttpStatus.NOT_FOUND, "找不到該頻道"),
    INSUFFICIENT_PERMISSIONS("CHANNEL_002", HttpStatus.FORBIDDEN, "權限不足，只有教師或助教能管理頻道"),
    NOT_SERVER_MEMBER("CHANNEL_003", HttpStatus.FORBIDDEN, "您非該伺服器的成員，無法存取頻道"),
    ADMIN_CHANNEL_ACCESS_DENIED("CHANNEL_004", HttpStatus.FORBIDDEN, "此為管理員頻道，學生無法存取"),
    MAX_CHANNELS_REACHED("CHANNEL_005", HttpStatus.BAD_REQUEST, "該伺服器的頻道數量已達上限 (50個)");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
