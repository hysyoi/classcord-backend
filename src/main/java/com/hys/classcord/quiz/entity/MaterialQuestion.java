package com.hys.classcord.quiz.entity;

import com.hys.classcord.core.entity.BaseEntity;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.quiz.dto.QuestionExplanation;
import com.hys.classcord.quiz.enums.QuestionType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "material_questions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MaterialQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "material_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_material_questions_material"))
    private Material material;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    @Builder.Default
    private QuestionType type = QuestionType.SINGLE_CHOICE;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, columnDefinition = "jsonb")
    private List<String> options;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correct_answer", nullable = false, columnDefinition = "jsonb")
    private List<String> correctAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation", columnDefinition = "jsonb")
    private QuestionExplanation explanation;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    public void markAsDeleted() {
        this.isDeleted = true;
    }
}
