package com.hys.classcord.ai.entity;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.core.entity.AuditableBaseEntity;
import com.hys.classcord.material.entity.Material;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "ai_sessions",
        indexes = {
            @Index(
                    name = "idx_ai_sessions_user_material",
                    columnList = "user_id, material_id, created_at DESC")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AiSession extends AuditableBaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sessions_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "material_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_sessions_material"))
    private Material material;
}
