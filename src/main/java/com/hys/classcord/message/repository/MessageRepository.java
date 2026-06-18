package com.hys.classcord.message.repository;

import com.hys.classcord.message.entity.Message;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    // 使用 JOIN FETCH 一次載入 User 實體，防範對應 MessageUserDto 時產生 N+1 問題
    // 當使用分頁查詢並搭配 JOIN FETCH 時，必須手動指定 countQuery 以免 Spring Data 無法正確產生計算總數的 SQL
    @Query(
            value = "SELECT m FROM Message m JOIN FETCH m.user u WHERE m.channel.id = :channelId",
            countQuery = "SELECT count(m) FROM Message m WHERE m.channel.id = :channelId")
    Page<Message> findByChannelId(@Param("channelId") UUID channelId, Pageable pageable);
}
