package com.hys.classcord.server.repository;

import com.hys.classcord.server.entity.ServerMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerMemberRepository extends JpaRepository<ServerMember, UUID> {

    Optional<ServerMember> findByServerIdAndUserId(UUID serverId, UUID userId);

    boolean existsByServerIdAndUserId(UUID serverId, UUID userId);

    // 用 EntityGraph 一次撈出成員與對應的使用者基本資料 (避免取得成員列表時 N+1)
    @EntityGraph(attributePaths = {"user"})
    List<ServerMember> findWithUserByServerId(UUID serverId);

    // 雙重 EntityGraph：一次查出「成員關係 + 伺服器 + 伺服器的擁有者」
    @EntityGraph(attributePaths = {"server", "server.owner"})
    List<ServerMember> findWithServerAndOwnerByUserId(UUID userId);

    long countByUserId(UUID userId);

    // 只取伺服器 ID，供 presence 廣播使用，避免載入整個 Server/Owner Entity
    @Query("select sm.server.id from ServerMember sm where sm.user.id = :userId")
    List<UUID> findServerIdsByUserId(@Param("userId") UUID userId);
}
