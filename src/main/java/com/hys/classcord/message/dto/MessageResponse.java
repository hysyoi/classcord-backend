package com.hys.classcord.message.dto;

import com.hys.classcord.message.entity.Message;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MessageResponse(
        UUID id, UUID channelId, MessageUserDto user, String content, Instant createdAt) {
    public static MessageResponse fromEntity(Message message) {
        return MessageResponse.builder()
                .id(message.getId())
                .channelId(message.getChannel().getId())
                .user(MessageUserDto.fromEntity(message.getUser()))
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
