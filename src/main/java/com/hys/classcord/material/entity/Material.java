package com.hys.classcord.material.entity;

import com.hys.classcord.core.entity.BaseEntity;
import com.hys.classcord.material.enums.MaterialStatus;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "message_id",
            nullable = false,
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MaterialStatus status = MaterialStatus.DISABLED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // 業務狀態切換方法
    public void markAsProcessing() {
        this.status = MaterialStatus.PROCESSING;
        this.errorMessage = null;
    }

    public void markAsEnabled() {
        this.status = MaterialStatus.ENABLED;
        this.errorMessage = null;
    }

    public void markAsFailed(String errorMessage) {
        this.status = MaterialStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
