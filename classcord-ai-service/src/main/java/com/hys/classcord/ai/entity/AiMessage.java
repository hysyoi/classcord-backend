package com.hys.classcord.ai.entity;

import com.hys.classcord.ai.enums.AiMessageRole;
import com.hys.classcord.core.entity.AuditableBaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "ai_messages",
        indexes = {
            @Index(
                    name = "idx_ai_messages_session_created",
                    columnList = "session_id, created_at DESC")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AiMessage extends AuditableBaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "session_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ai_messages_session"))
    private AiSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private AiMessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
}
