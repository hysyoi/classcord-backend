package com.hys.classcord.quiz.exception;

import com.hys.classcord.core.exception.BusinessException;
import com.hys.classcord.quiz.enums.QuizErrorCode;

public class QuizException extends BusinessException {

    public QuizException(QuizErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatus());
    }

    public QuizException(QuizErrorCode errorCode, String customMessage) {
        super(errorCode.getCode(), customMessage, errorCode.getStatus());
    }
}
