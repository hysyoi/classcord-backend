package com.hys.classcord.material.exception;

import com.hys.classcord.core.exception.BusinessException;
import com.hys.classcord.material.enums.MaterialErrorCode;

public class MaterialException extends BusinessException {
    public MaterialException(MaterialErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public MaterialException(MaterialErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
