package com.hys.classcord.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageRequest(
        @NotBlank(message = "訊息內容不能為空") @Size(max = 2000, message = "訊息內容不能超過 2000 個字元")
                String content) {}
