package com.hys.classcord.server.repository;

import com.hys.classcord.server.entity.Server;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerRepository extends JpaRepository<Server, UUID> {
    // 在查詢伺服器時，立刻對該行數據加上排他鎖（Pessimistic Write Lock）
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Server s WHERE s.id = :id")
    Optional<Server> findByIdForUpdate(@Param("id") UUID id);

    long countByOwnerId(UUID ownerId);
}
