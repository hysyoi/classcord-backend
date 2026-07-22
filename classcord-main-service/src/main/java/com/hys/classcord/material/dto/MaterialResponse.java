package com.hys.classcord.material.dto;

import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialStatus;
import java.util.UUID;

public record MaterialResponse(
        UUID id,
        UUID messageId,
        String fileUrl,
        String fileType,
        String originalName,
        MaterialStatus status) {
    public static MaterialResponse fromEntity(Material material) {
        if (material == null) {
            return null;
        }
        return new MaterialResponse(
                material.getId(),
                material.getMessage().getId(),
                material.getFileUrl(),
                material.getFileType(),
                material.getOriginalName(),
                material.getStatus());
    }
}
