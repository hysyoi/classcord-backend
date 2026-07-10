package com.hys.classcord.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 為了安全性，不在在 URL 參數中傳遞敏感資訊 */
public record ForgotPasswordRequest(
        @NotBlank(message = "電子郵件不能為空")
                @Email(message = "電子郵件格式不正確")
                @Size(max = 255, message = "Email長度不可超過255字元")
                String email,
        @NotBlank(message = "人機驗證 Token 不能為空") String turnstileToken) {}
