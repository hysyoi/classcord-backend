package com.hys.classcord.channel.dto;

import com.hys.classcord.channel.enums.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateChannelRequest(
        @NotBlank(message = "頻道名稱不可為空") @Size(max = 100, message = "頻道名稱長度不可超過 100 字元") String name,
        @NotNull(message = "頻道類型不可為空") ChannelType type) {}
