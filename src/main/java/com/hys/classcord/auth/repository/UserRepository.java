package com.hys.classcord.auth.repository;

import com.hys.classcord.auth.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // 檢查重複註冊
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);
}
