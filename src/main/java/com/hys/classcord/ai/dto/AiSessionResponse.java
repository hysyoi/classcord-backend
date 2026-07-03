package com.hys.classcord.ai.dto;

import com.hys.classcord.ai.entity.AiSession;
import java.time.Instant;
import java.util.UUID;

public record AiSessionResponse(UUID id, UUID materialId, Instant createdAt) {
    public static AiSessionResponse fromEntity(AiSession entity) {
        return new AiSessionResponse(
                entity.getId(), entity.getMaterial().getId(), entity.getCreatedAt());
    }
}
