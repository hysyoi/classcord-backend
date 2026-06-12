package com.hys.classcord.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "憑證 Token 不能為空") String token,
        @NotBlank(message = "新密碼不能為空") @Size(min = 8, max = 100, message = "新密碼長度需介於 8~100 字元")
                String newPassword) {}
