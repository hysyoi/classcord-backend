package com.hys.classcord.core.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    // 統一攔截 DTO 參數驗證失敗 (400 Bad Request) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        // 從異常物件中抽取出所有校驗失敗的欄位與對應的提示訊息
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        log.warn("參數驗證失敗 -> 錯誤欄位數: {}", errors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        Map.of(
                                "status",
                                HttpStatus.BAD_REQUEST.value(),
                                "error",
                                "Bad Request",
                                "message",
                                "輸入欄位驗證失敗，請檢查格式",
                                "errors",
                                errors, // 將詳細的欄位錯誤（例如: {"email": "必須是格式正確的電子郵件"}）回傳給前端
                                "timestamp",
                                LocalDateTime.now().toString()));
    }

    // @ExceptionHandler(AsyncRequestNotUsableException.class)
    // public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
    //     log.warn("【連線通知】使用者在 AI 串流結束時已提前離開連線。");
    // }
}
