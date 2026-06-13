package com.hys.classcord.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateChannelRequest(
        @NotBlank(message = "頻道名稱不可為空") @Size(max = 100, message = "頻道名稱長度不可超過 100 字元")
                String name) {}
