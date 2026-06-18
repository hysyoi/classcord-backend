package com.hys.classcord.message.dto;

import com.hys.classcord.auth.entity.User;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MessageUserDto(UUID id, String username, String avatarUrl) {
    public static MessageUserDto fromEntity(User user) {
        if (user == null) return null;
        return MessageUserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}
