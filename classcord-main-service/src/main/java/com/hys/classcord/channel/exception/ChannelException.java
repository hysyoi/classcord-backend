package com.hys.classcord.channel.exception;

import com.hys.classcord.channel.enums.ChannelErrorCode;
import com.hys.classcord.core.exception.BusinessException;

public class ChannelException extends BusinessException {
    public ChannelException(ChannelErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public ChannelException(ChannelErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
