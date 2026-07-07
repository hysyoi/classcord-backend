package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.QuizQuestion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, UUID> {

    /** 一次 JOIN FETCH question，避免後續存取 MaterialQuestion 時的 N+1 查詢 */
    @EntityGraph(attributePaths = {"question"})
    List<QuizQuestion> findWithQuestionsByQuizId(UUID quizId);

    /** 獲取該教材所有已被提交的答題記錄 (JOIN FETCH 考題主體) */
    @Query(
            "SELECT qq FROM QuizQuestion qq JOIN FETCH qq.question q WHERE q.material.id = :materialId AND qq.quiz.score IS NOT NULL")
    List<QuizQuestion> findSubmittedQuestionsByMaterialId(@Param("materialId") UUID materialId);
}
