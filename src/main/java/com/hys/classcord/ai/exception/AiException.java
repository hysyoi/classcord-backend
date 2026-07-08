package com.hys.classcord.ai.exception;

import com.hys.classcord.ai.enums.AiErrorCode;
import com.hys.classcord.core.exception.BusinessException;

public class AiException extends BusinessException {

    public AiException(AiErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public AiException(AiErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
