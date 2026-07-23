package com.hys.classcord.server.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ServerErrorCode {
    SERVER_NOT_FOUND("SERVER_001", HttpStatus.NOT_FOUND, "找不到該伺服器"),
    NOT_SERVER_OWNER("SERVER_002", HttpStatus.FORBIDDEN, "只有伺服器擁有者才能執行此操作"),
    ALREADY_SERVER_MEMBER("SERVER_003", HttpStatus.CONFLICT, "使用者已是該伺服器的成員"),
    NOT_SERVER_MEMBER("SERVER_004", HttpStatus.FORBIDDEN, "使用者非該伺服器成員，無權執行此操作"),
    USER_NOT_FOUND("SERVER_005", HttpStatus.NOT_FOUND, "找不到該使用者"),
    CANNOT_LEAVE_OWNER("SERVER_006", HttpStatus.BAD_REQUEST, "伺服器擁有者不能退出伺服器，請先轉讓擁有者或解散伺服器"),
    MAX_SERVERS_CREATED("SERVER_007", HttpStatus.BAD_REQUEST, "建立的伺服器數量已達上限 (10個)"),
    MAX_SERVERS_JOINED("SERVER_008", HttpStatus.BAD_REQUEST, "加入的伺服器數量已達上限 (20個)"),
    INSUFFICIENT_PERMISSIONS("SERVER_009", HttpStatus.FORBIDDEN, "權限不足，無法執行此操作");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
