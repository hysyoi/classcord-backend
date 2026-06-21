package com.hys.classcord.material.repository;

import com.hys.classcord.material.entity.Material;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

// todo 檢查索引
@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {

    // 依據單一 messageId 尋找教材
    Optional<Material> findByMessageId(UUID messageId);

    // 批次查詢：傳入多個 messageId，一次性撈出所有對應的教材（用於分頁歷史訊息時）
    List<Material> findByMessageIdIn(List<UUID> messageIds);

    // 統計全站所有教材的容量總和（若是剛啟動無教材則返回 0）
    @Query("SELECT COALESCE(SUM(m.fileSize), 0) FROM Material m")
    long sumAllFileSizes();

    // 統計特定班級（Server）上傳教材的容量總和
    @Query(
            "SELECT COALESCE(SUM(m.fileSize), 0) FROM Material m WHERE m.message.channel.server.id = :serverId")
    long sumFileSizesByServerId(@Param("serverId") UUID serverId);
}
