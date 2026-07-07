package com.hys.classcord.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "使用者名稱不可為空") @Size(min = 2, max = 30, message = "使用者名稱長度需介於2~30字元")
                String username,
        @NotBlank(message = "Email不可為空")
                @Email(message = "Email格式錯誤")
                @Size(max = 255, message = "Email長度不可超過255字元")
                String email,
        @NotBlank(message = "密碼不可為空") @Size(min = 8, max = 100, message = "密碼長度需介於 8~100 字元")
                String password,
        String turnstileToken) {
    public RegisterRequest(String username, String email, String password) {
        this(username, email, password, null);
    }
}
