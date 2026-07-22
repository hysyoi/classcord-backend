package com.hys.classcord.server.exception;

import com.hys.classcord.core.exception.BusinessException;
import com.hys.classcord.server.enums.ServerErrorCode;

public class ServerException extends BusinessException {
    public ServerException(ServerErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public ServerException(ServerErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
