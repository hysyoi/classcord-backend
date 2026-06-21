package com.hys.classcord.message.dto;

import com.hys.classcord.material.dto.MaterialResponse;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.message.entity.Message;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MessageResponse(
        UUID id,
        UUID channelId,
        MessageUserDto user,
        String content,
        Instant createdAt,
        MaterialResponse material // 新增：可為空的教材附件回應
        ) {
    // 保留原有的方法，預設沒有教材
    public static MessageResponse fromEntity(Message message) {
        return fromEntity(message, null);
    }

    // 新增：重載方法，允許傳入對應的教材實體
    public static MessageResponse fromEntity(Message message, Material material) {
        return MessageResponse.builder()
                .id(message.getId())
                .channelId(message.getChannel().getId())
                .user(MessageUserDto.fromEntity(message.getUser()))
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .material(MaterialResponse.fromEntity(material))
                .build();
    }
}
