package com.hys.classcord.ai.repository;

import com.hys.classcord.ai.entity.AiMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    List<AiMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Limit limit);

    /** 獲取特定教材下，學生向 AI 助教提問的所有歷史訊息內容 */
    @Query(
            "SELECT m.content FROM AiMessage m WHERE m.session.material.id = :materialId AND m.role = AiMessageRole.USER ORDER BY m.createdAt DESC")
    List<String> findUserMessagesByMaterialId(@Param("materialId") UUID materialId, Limit limit);
}
