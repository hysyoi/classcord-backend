package com.hys.classcord.server.repository;

import com.hys.classcord.server.entity.Server;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerRepository extends JpaRepository<Server, UUID> {
    // 使用原生 SQL 明確指定 FOR UPDATE，避免 Hibernate 6.x 生成 Seata 無法解析的 FOR NO KEY UPDATE
    @Query(value = "SELECT * FROM servers WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Server> findByIdForUpdate(@Param("id") UUID id);

    long countByOwnerId(UUID ownerId);
}
