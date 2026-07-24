package com.hys.classcord.auth.repository;

import com.hys.classcord.auth.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // 檢查重複註冊
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    // 使用原生 SQL 明確指定 FOR UPDATE，避免 Hibernate 6.x 生成 Seata 無法解析的 FOR NO KEY UPDATE
    @Query(value = "SELECT * FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
