package com.hys.classcord.channel.dto;

import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ChannelResponse(
        UUID id, UUID serverId, String name, ChannelType type, Integer position) {
    public static ChannelResponse fromEntity(Channel channel) {
        return ChannelResponse.builder()
                .id(channel.getId())
                .serverId(channel.getServer().getId()) // 只拿外鍵 ID
                .name(channel.getName())
                .type(channel.getType())
                .position(channel.getPosition())
                .build();
    }
}
