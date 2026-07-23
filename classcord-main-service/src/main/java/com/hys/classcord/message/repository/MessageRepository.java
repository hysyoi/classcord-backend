package com.hys.classcord.message.repository;

import com.hys.classcord.message.entity.Message;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // 使用 EntityGraph 在資料庫分頁的同時，批次 Eager 載入 user，避免 N+1 問題
    // materials (一對多) 改用 @BatchSize 批次懶載入，避免 Cartesian product 導致記憶體分頁警告
    @EntityGraph(attributePaths = {"user"})
    Page<Message> findByChannelId(UUID channelId, Pageable pageable);
}
