package com.hys.classcord.auth.exception;

import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.core.exception.BusinessException;

public class AuthException extends BusinessException {
    public AuthException(AuthErrorCode errorCode) {
        // 把 code, message, status 通通往上丟
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }
}
