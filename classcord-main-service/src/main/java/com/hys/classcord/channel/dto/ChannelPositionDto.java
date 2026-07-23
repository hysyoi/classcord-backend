package com.hys.classcord.channel.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChannelPositionDto(
        @NotNull(message = "頻道 ID 不可為空") UUID id,
        @NotNull(message = "排序位置不可為空") Integer position) {}
