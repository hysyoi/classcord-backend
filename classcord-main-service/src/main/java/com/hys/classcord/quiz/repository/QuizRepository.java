package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.Quiz;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {
    /** 透過測驗 ID 輕量級查詢所屬的 Server ID */
    @Query("SELECT q.material.message.channel.server.id FROM Quiz q WHERE q.id = :quizId")
    UUID findServerIdById(@Param("quizId") UUID quizId);

    List<Quiz> findByUserIdAndMaterialIdOrderByCreatedAtDesc(UUID userId, UUID materialId);

    List<Quiz> findByUserIdAndMaterialIdAndScoreIsNotNullOrderByCreatedAtDesc(
            UUID userId, UUID materialId);

    List<Quiz> findByUserIdAndMaterialIdAndScoreIsNull(UUID userId, UUID materialId);
}
