package com.hys.classcord.ai.repository;

import com.hys.classcord.ai.entity.AiSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiSessionRepository extends JpaRepository<AiSession, UUID> {
    List<AiSession> findByUserIdAndMaterialIdOrderByCreatedAtDesc(UUID userId, UUID materialId);

    @EntityGraph(attributePaths = {"user", "material"})
    Optional<AiSession> findWithUserAndMaterialById(UUID id);
}
