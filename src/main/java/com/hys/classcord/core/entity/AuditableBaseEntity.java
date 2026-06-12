package com.hys.classcord.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class) // 啟用實體監聽器
@Getter
public abstract class AuditableBaseEntity extends BaseEntity {
    @CreatedDate // 標註建立時間自動填入
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
