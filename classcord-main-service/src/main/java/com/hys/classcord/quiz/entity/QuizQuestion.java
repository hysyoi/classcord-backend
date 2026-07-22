package com.hys.classcord.quiz.entity;

import com.hys.classcord.core.entity.BaseEntity;
import jakarta.persistence.*;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "quiz_questions",
        indexes = {@Index(name = "idx_quiz_questions_quiz_id", columnList = "quiz_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class QuizQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "quiz_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_questions_quiz"))
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "question_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_questions_question"))
    private MaterialQuestion question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_answer", columnDefinition = "jsonb")
    private List<String> userAnswer;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    public void submitAnswer(List<String> userAnswer, Boolean isCorrect) {
        this.userAnswer = userAnswer;
        this.isCorrect = isCorrect;
    }
}
