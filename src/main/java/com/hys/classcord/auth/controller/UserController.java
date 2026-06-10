package com.hys.classcord.auth.controller;

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
@Tag(name = "帳戶模組 (Auth)", description = "處理使用者註冊、個人資料獲取等 API")
public class UserController {

    private final UserService userService;

    /** 註冊全新使用者 */
    @PostMapping
    @Operation(summary = "註冊全新使用者", description = "傳入用戶名、Email，系統會自動生成 UUID v7 並寫入資料庫")
    public ResponseEntity<User> registerUser(@RequestBody User user) {
        User created = userService.createUser(user);
        return ResponseEntity.ok(created);
    }

    /** 獲取使用者資料 */
    @GetMapping("/{id}")
    @Operation(summary = "獲取使用者個人資料", description = "根據網址傳入的 UUID 查詢用戶")
    public ResponseEntity<User> getUserProfile(@PathVariable UUID id) {
        User user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }
}
