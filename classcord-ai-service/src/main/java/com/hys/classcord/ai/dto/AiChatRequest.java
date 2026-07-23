package com.hys.classcord.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(
        @NotBlank(message = "提問內容不能為空") @Size(max = 1000, message = "提問內容長度不能超過 1000 個字元")
                String message) {}
