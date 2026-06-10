package com.hys.classcord.auth.repository;

import com.hys.classcord.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // 檢查重複註冊
    boolean existsByEmail(String email);
}
