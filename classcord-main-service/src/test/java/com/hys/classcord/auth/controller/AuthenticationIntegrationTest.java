package com.hys.classcord.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.auth.dto.PendingUserDto;
import com.hys.classcord.auth.dto.RegisterRequest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.repository.UserIdentityRepository;
import com.hys.classcord.auth.repository.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public class AuthenticationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private UserRepository userRepository;

    @Autowired private UserIdentityRepository userIdentityRepository;

    @Autowired private ObjectMapper objectMapper;

    private record VerificationToken(String redisKey, String token) {}

    // 送出註冊請求，並從 Redis 撈出對應的 Email 驗證 Token，供各測試方法重用
    private VerificationToken register(String email, String username) throws Exception {
        RegisterRequest registerReq = new RegisterRequest(username, email, "Password123!");

        mockMvc.perform(
                        post("/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isAccepted()); // 驗證回傳 202 Accepted

        Set<String> keys = redisTemplate.keys("AUTH:VERIFY_EMAIL:*");
        assertNotNull(keys);
        assertEquals(1, keys.size(), "Redis 中應該要有一個驗證 Email 的 Token");

        String redisKey = keys.iterator().next();
        String token = redisKey.substring(redisKey.lastIndexOf(":") + 1); // 提取出 UUID Token
        return new VerificationToken(redisKey, token);
    }

    // 註冊後應該在 Redis 產生一筆驗證 Email 用的 Token，內容要跟註冊資訊吻合
    @Test
    void testRegister_CreatesRedisVerificationToken() throws Exception {
        String testEmail = "integration-test@example.com";
        VerificationToken verification = register(testEmail, "TestUser");

        // 驗證 Redis 內存的值是否與註冊資訊吻合
        String jsonStr = redisTemplate.opsForValue().get(verification.redisKey());
        assertNotNull(jsonStr);
        PendingUserDto pendingUser = objectMapper.readValue(jsonStr, PendingUserDto.class);
        assertEquals(testEmail.toLowerCase(), pendingUser.getEmail()); // 註冊時會自動轉小寫
        assertEquals("TestUser", pendingUser.getUsername());
    }

    // 點擊開通連結後，應該正式建立 User 與登入憑證，並銷毀已使用過的 Redis Token
    @Test
    void testActivate_CreatesUserAndDestroysToken() throws Exception {
        String testEmail = "activate-test@example.com";
        VerificationToken verification = register(testEmail, "ActivateUser");

        // 模擬點擊開通連結 GET /v1/auth/activate?token=xxx
        mockMvc.perform(get("/v1/auth/activate").param("token", verification.token()))
                .andExpect(status().isFound()); // 驗證回傳 302 Found (重導向至前端)

        // 觸發在測試交易中註冊的 afterCommit 回呼以銷毀 Redis Token
        triggerAfterCommitCallbacks();

        // 1. 驗證資料庫中是否成功建立了 User 記錄
        User savedUser = userRepository.findByEmail(testEmail.toLowerCase()).orElse(null);
        assertNotNull(savedUser, "資料庫中應該要存在該使用者");
        assertEquals("ActivateUser", savedUser.getUsername());
        assertNotNull(savedUser.getCreatedAt(), "JPA Auditing 應該要自動填入建立時間 (createdAt)");

        // 2. 驗證資料庫中是否建立了本地憑證記錄 (UserIdentity)
        UserIdentity savedIdentity =
                userIdentityRepository
                        .findByUserAndProvider(savedUser, AuthProvider.LOCAL)
                        .orElse(null);
        assertNotNull(savedIdentity, "應該要存在本地登入憑證");
        assertNotNull(savedIdentity.getPasswordHash(), "密碼雜湊值不能為空");

        // 3. 驗證 Redis 中的 Token 是否已經被銷毀 (一次性使用)
        String expiredValue = redisTemplate.opsForValue().get(verification.redisKey());
        assertNull(expiredValue, "Token 使用後應立即從 Redis 中刪除");
    }
}
