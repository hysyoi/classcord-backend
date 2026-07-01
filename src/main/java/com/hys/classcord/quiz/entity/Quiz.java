package com.hys.classcord.quiz.entity;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.core.entity.BaseEntity;
import com.hys.classcord.material.entity.Material;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(
        name = "quizzes",
        indexes = {
            @Index(name = "idx_quizzes_user_created", columnList = "user_id, created_at DESC")
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Quiz extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_quizzes_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "material_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_quizzes_material"))
    private Material material;

    @Column(name = "score")
    private Integer score;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public void updateScore(Integer score) {
        this.score = score;
    }
}
