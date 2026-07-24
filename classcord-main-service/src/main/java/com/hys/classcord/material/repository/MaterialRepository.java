package com.hys.classcord.material.repository;

import com.hys.classcord.material.entity.Material;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

// todo 檢查索引
@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {

    // 依據單一 messageId 尋找教材
    Optional<Material> findByMessageId(UUID messageId);

    // 根據 教材Id 尋找 ServerId
    // todo 考慮用更安全的Optional（但是要寫更多判斷）
    @Query("SELECT m.message.channel.server.id FROM Material m WHERE m.id = :id")
    UUID findServerIdById(@Param("id") UUID id);

    // 使用原生 SQL 明確指定 FOR UPDATE，避免 Hibernate 6.x 自動生成 FOR NO KEY UPDATE
    // 導致 Seata 內建的 Druid 1.2.7 SQL Parser 無法解析
    @Query(value = "SELECT * FROM materials WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Material> findByIdForUpdate(@Param("id") UUID id);

    // 一次性 JOIN FETCH 教材對應的貼文、頻道、伺服器資訊，用於在事務外讀取 Metadata
    @EntityGraph(attributePaths = {"message.channel.server"})
    Optional<Material> findWithAssociationsById(UUID id);

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
