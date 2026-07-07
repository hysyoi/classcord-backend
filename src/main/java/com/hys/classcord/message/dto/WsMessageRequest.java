package com.hys.classcord.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record WsMessageRequest(
        @NotNull(message = "頻道 ID 不能為空") UUID channelId,
        @NotBlank(message = "訊息內容不能為空") @Size(max = 2000, message = "訊息內容不能超過 2000 個字元")
                String content) {}
