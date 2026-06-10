package com.hys.classcord.core.exception;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 1. 統一攔截業務異常 (4xx 系列) */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException ex) {
        log.warn(
                "業務異常攔截 -> [{}] 狀態碼: {}, 原因: {}",
                ex.getCode(),
                ex.getStatus().value(),
                ex.getMessage());

        return ResponseEntity.status(ex.getStatus())
                .body(
                        Map.of(
                                "code", ex.getCode(),
                                "status", ex.getStatus().value(),
                                "message", ex.getMessage(),
                                "timestamp", LocalDateTime.now().toString()));
    }

    /** 2. 系統錯誤兜底防禦線 (500 系列) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleSystemException(Exception ex) {
        // 這裡會把完整的錯誤堆疊印在 Log 裡，但前端只會看到溫和的提示
        log.error("系統未預期崩潰: ", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        Map.of(
                                "status",
                                500,
                                "error",
                                "Internal Server Error",
                                "message",
                                "系統繁忙，請稍後再試",
                                "timestamp",
                                LocalDateTime.now().toString()));
    }
}
