package com.hys.classcord.ai.entity;

import com.hys.classcord.core.entity.AuditableBaseEntity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
        name = "ai_sessions",
        indexes = {
            @Index(
                    name = "idx_ai_sessions_user_material",
                    columnList = "user_id, material_id, created_at DESC"),
            @Index(name = "idx_ai_sessions_material_id", columnList = "material_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AiSession extends AuditableBaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
}
