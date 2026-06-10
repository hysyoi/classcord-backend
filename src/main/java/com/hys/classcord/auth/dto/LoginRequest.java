package com.hys.classcord.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email不可為空")
                @Email(message = "Email格式錯誤")
                @Size(max = 255, message = "Email長度不可超過255字元")
                String email,

        // 由於沒有歷史包袱（例如老舊用戶），所以沒必要讓長度不合法的請求進來
        @NotBlank(message = "密碼不可為空") @Size(min = 8, max = 100, message = "密碼長度需介於8~100字元")
                String password) {}
