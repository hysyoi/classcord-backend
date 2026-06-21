package com.hys.classcord.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.auth.dto.PendingUserDto;
import com.hys.classcord.auth.dto.RegisterRequest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.repository.UserIdentityRepository;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.service.MailService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test") // 載入 application-test.yml 的測試環境設定
@AutoConfigureMockMvc // 自動配置 MockMvc，用來模擬 HTTP 請求
@Transactional // 測試結束後自動 Rollback 資料庫，保持測試資料庫乾淨
public class AuthenticationIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private UserRepository userRepository;

    @Autowired private UserIdentityRepository userIdentityRepository;

    @Autowired private ObjectMapper objectMapper;

    // 用 MockBean 擋掉郵件發送，避免測試時真的連線 Mailpit 或外部伺服器
    @MockBean private MailService mailService;

    // todo 測試環境的redis處理機制
    @BeforeEach
    void setUp() {
        // 每次測試前清空 Redis 中的相關測試 Key，避免干擾
        Set<String> keys = redisTemplate.keys("AUTH:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void testRegisterAndActivateFlow() throws Exception {
        // --- 階段一：註冊帳號 ---
        String testEmail = "integration-test@example.com";
        RegisterRequest registerReq = new RegisterRequest("TestUser", testEmail, "Password123!");

        // 模擬 POST /v1/auth/register
        mockMvc.perform(
                        post("/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isAccepted()); // 驗證回傳 202 Accepted

        // --- 階段二：從 Redis 中撈出 Token ---
        // 註冊成功後，Redis 中應該會產生一個 AUTH:VERIFY_EMAIL:<UUID-Token> 的 Key
        Set<String> keys = redisTemplate.keys("AUTH:VERIFY_EMAIL:*");
        assertNotNull(keys);
        assertEquals(1, keys.size(), "Redis 中應該要有一個驗證 Email 的 Token");

        String redisKey = keys.iterator().next();
        String token = redisKey.substring(redisKey.lastIndexOf(":") + 1); // 提取出 UUID Token

        // 驗證 Redis 內存的值是否與註冊資訊吻合
        String jsonStr = redisTemplate.opsForValue().get(redisKey);
        assertNotNull(jsonStr);
        PendingUserDto pendingUser = objectMapper.readValue(jsonStr, PendingUserDto.class);
        assertEquals(testEmail.toLowerCase(), pendingUser.getEmail()); // 註冊時會自動轉小寫
        assertEquals("TestUser", pendingUser.getUsername());

        // --- 階段三：點擊開通連結 ---
        // 模擬 GET /v1/auth/activate?token=xxx
        mockMvc.perform(get("/v1/auth/activate").param("token", token))
                .andExpect(status().isFound()); // 驗證回傳 302 Found (重導向至前端)

        // --- 階段四：驗證資料庫落地與 Redis 銷毀 ---
        // 1. 驗證資料庫中是否成功建立了 User 記錄
        User savedUser = userRepository.findByEmail(testEmail.toLowerCase()).orElse(null);
        assertNotNull(savedUser, "資料庫中應該要存在該使用者");
        assertEquals("TestUser", savedUser.getUsername());
        assertNotNull(savedUser.getCreatedAt(), "JPA Auditing 應該要自動填入建立時間 (createdAt)");

        // 2. 驗證資料庫中是否建立了本地憑證記錄 (UserIdentity)
        UserIdentity savedIdentity =
                userIdentityRepository
                        .findByUserAndProvider(savedUser, AuthProvider.LOCAL)
                        .orElse(null);
        assertNotNull(savedIdentity, "應該要存在本地登入憑證");
        assertNotNull(savedIdentity.getPasswordHash(), "密碼雜湊值不能為空");

        // 3. 驗證 Redis 中的 Token 是否已經被銷毀 (一次性使用)
        String expiredValue = redisTemplate.opsForValue().get(redisKey);
        assertNull(expiredValue, "Token 使用後應立即從 Redis 中刪除");
    }
}
