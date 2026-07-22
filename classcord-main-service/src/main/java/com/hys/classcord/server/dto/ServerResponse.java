package com.hys.classcord.server.dto;

import com.hys.classcord.server.entity.Server;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ServerResponse(UUID id, String name, UUID ownerId, Instant createdAt) {
    public static ServerResponse fromEntity(Server server) {
        return ServerResponse.builder()
                .id(server.getId())
                .name(server.getName())
                .ownerId(server.getOwner().getId())
                .createdAt(server.getCreatedAt())
                .build();
    }
}
