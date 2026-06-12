package com.hys.classcord.auth.controller;

import com.hys.classcord.auth.dto.UserProfileResponse;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "使用者模組", description = "獲取使用者資料等 API")
public class UserController {

    private final UserService userService;

    /** 獲取使用者資料 */
    @GetMapping("/{id}")
    @Operation(summary = "獲取使用者個人資料", description = "根據網址傳入的 UUID 查詢用戶")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable UUID id) {
        User user = userService.getUserById(id);
        return ResponseEntity.ok(UserProfileResponse.fromEntity(user));
    }
}
