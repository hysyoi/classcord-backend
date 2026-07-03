package com.hys.classcord.message.dto;

import com.hys.classcord.material.dto.MaterialResponse;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.message.entity.Message;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MessageResponse(
        UUID id,
        UUID channelId,
        MessageUserDto user,
        String content,
        Instant createdAt,
        List<MaterialResponse> materials // 改為複數列表以支援一對多教材附件
        ) {
    // 預設轉換，從實體中讀取載入好的 materials
    public static MessageResponse fromEntity(Message message) {
        return fromEntity(message, message.getMaterials());
    }

    // 重載：由外部直接傳入材料列表，適用於新發布尚未重新查詢的場景
    public static MessageResponse fromEntity(Message message, List<Material> materials) {
        return MessageResponse.builder()
                .id(message.getId())
                .channelId(message.getChannel().getId())
                .user(MessageUserDto.fromEntity(message.getUser()))
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .materials(
                        materials != null
                                ? materials.stream().map(MaterialResponse::fromEntity).toList()
                                : Collections.emptyList())
                .build();
    }
}
