package com.hys.classcord.ai.repository;

import com.hys.classcord.ai.entity.AiMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    List<AiMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Limit limit);
}
