package com.hys.classcord.auth.service;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** 根據 ID 查詢使用者 */
    public User getUserById(UUID id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到該使用者！"));
    }
}
