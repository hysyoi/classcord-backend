package com.hys.classcord.core.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 全域業務異常基底（所有業務邏輯錯誤都要繼承此類） */
@Getter
public abstract class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
