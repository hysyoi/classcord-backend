package com.hys.classcord.message.exception;

import com.hys.classcord.core.exception.BusinessException;
import com.hys.classcord.message.enums.MessageErrorCode;

public class MessageException extends BusinessException {
    public MessageException(MessageErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public MessageException(MessageErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
