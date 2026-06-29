package com.hys.classcord.material.entity;

import com.hys.classcord.core.entity.BaseEntity;
import com.hys.classcord.message.entity.Message;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "materials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Material extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "message_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_materials_message"))
    private Message message;

    @Column(name = "file_url", nullable = false, unique = true, length = 500)
    private String fileUrl;

    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize; // 單位：Bytes
}
