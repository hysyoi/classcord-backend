package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.MaterialQuestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialQuestionRepository extends JpaRepository<MaterialQuestion, UUID> {

    List<MaterialQuestion> findByMaterialIdAndIsDeletedFalse(UUID materialId);

    long countByMaterialIdAndIsDeletedFalse(UUID materialId);

    Optional<MaterialQuestion> findByIdAndIsDeletedFalse(UUID id);

    /** 透過問題 ID 輕量級查詢所屬的 Server ID */
    @Query(
            "SELECT q.material.message.channel.server.id FROM MaterialQuestion q WHERE q.id = :questionId")
    UUID findServerIdById(@Param("questionId") UUID questionId);

    /** 依教材 ID 在 PostgreSQL 資料庫層隨機抽選 N 筆未刪除的題目 */
    @Query(
            value =
                    "SELECT * FROM material_questions WHERE material_id = :materialId AND is_deleted = false ORDER BY RANDOM() LIMIT :limit",
            nativeQuery = true)
    List<MaterialQuestion> findRandomQuestionsByMaterialId(
            @Param("materialId") UUID materialId, @Param("limit") int limit);
}
