package com.hys.classcord.server.dto;

import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ServerMemberResponse(
        UUID id,
        UUID userId,
        String username,
        String avatarUrl,
        ServerRole role,
        Instant joinedAt,
        boolean online) {
    public static ServerMemberResponse fromEntity(ServerMember member, boolean online) {
        return ServerMemberResponse.builder()
                .id(member.getId())
                .userId(member.getUser().getId())
                .username(member.getUser().getUsername())
                .avatarUrl(member.getUser().getAvatarUrl())
                .role(member.getRole())
                .joinedAt(member.getJoinedAt())
                .online(online)
                .build();
    }
}
