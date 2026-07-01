package com.hys.classcord.ai.dto;

import com.hys.classcord.ai.entity.AiMessage;
import java.time.Instant;
import java.util.UUID;

public record AiMessageResponse(UUID id, String role, String content, Instant createdAt) {
    public static AiMessageResponse fromEntity(AiMessage entity) {
        return new AiMessageResponse(
                entity.getId(),
                entity.getRole().name().toLowerCase(),
                entity.getContent(),
                entity.getCreatedAt());
    }
}
