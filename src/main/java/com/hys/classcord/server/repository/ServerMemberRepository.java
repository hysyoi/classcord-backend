package com.hys.classcord.server.repository;

import com.hys.classcord.server.entity.ServerMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServerMemberRepository extends JpaRepository<ServerMember, UUID> {

    Optional<ServerMember> findByServerIdAndUserId(UUID serverId, UUID userId);

    boolean existsByServerIdAndUserId(UUID serverId, UUID userId);

    // 用 JOIN FETCH 一次撈出成員與對應的使用者基本資料 (避免取得成員列表時 N+1)
    @Query("SELECT sm FROM ServerMember sm JOIN FETCH sm.user WHERE sm.server.id = :serverId")
    List<ServerMember> findByServerIdWithUser(@Param("serverId") UUID serverId);

    // 雙重 JOIN FETCH：一次查出「成員關係 + 伺服器 + 伺服器的擁有者」
    @Query(
            "SELECT sm FROM ServerMember sm "
                    + "JOIN FETCH sm.server s "
                    + "JOIN FETCH s.owner "
                    + "WHERE sm.user.id = :userId")
    List<ServerMember> findByUserIdWithServerAndOwner(@Param("userId") UUID userId);

    long countByUserId(UUID userId);
}
