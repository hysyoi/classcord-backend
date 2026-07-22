package com.hys.classcord.common.dto;

import com.hys.classcord.material.enums.MaterialStatus;
import java.util.UUID;
import lombok.Builder;

@Builder
public record InternalMaterialDto(
        UUID id,
        MaterialStatus status,
        String fileUrl,
        String originalName,
        String fileType,
        Long fileSize,
        UUID serverId,
        UUID channelId
) {}
