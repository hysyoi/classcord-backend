package com.hys.classcord.channel.repository;

import com.hys.classcord.channel.entity.Channel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {
    // 依據 position 欄位升遞排序，方便前端呈現頻道列表的順序
    List<Channel> findByServerIdOrderByPositionAsc(UUID serverId);

    // 查詢指定伺服器中目前最大的 position 數值
    @Query("SELECT MAX(c.position) FROM Channel c WHERE c.server.id = :serverId")
    Integer findMaxPositionByServerId(@Param("serverId") UUID serverId);

    // 計算該伺服器目前的頻道數量
    long countByServerId(UUID serverId);
}
