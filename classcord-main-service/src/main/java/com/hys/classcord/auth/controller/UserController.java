package com.hys.classcord.auth.controller;

import com.hys.classcord.auth.dto.UserProfileResponse;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "使用者模組", description = "獲取使用者資料等 API")
public class UserController {

    private final UserService userService;

    /** 獲取目前登入使用者資料 */
    @GetMapping("/me")
    @Operation(summary = "獲取目前登入使用者個人資料", description = "根據 JWT 中的認證資訊查詢當前用戶資料")
    public ResponseEntity<UserProfileResponse> getUserProfile(
            @AuthenticationPrincipal UUID userId) {
        User user = userService.getUserById(userId);
        return ResponseEntity.ok(UserProfileResponse.fromEntity(user));
    }
}
