package com.hys.classcord.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.auth.dto.PendingUserDto;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import com.hys.classcord.auth.repository.UserIdentityRepository;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.core.config.AppUrlProperties;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 身份驗證(Authentication) */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final JwtUtils jwtUtils; // 發行 JWT 的工具類
    private final PasswordEncoder passwordEncoder; // 加鹽雜湊工具
    private final StringRedisTemplate redisTemplate;
    private final VerificationTokenService tokenService; // 驗證碼服務
    private final ObjectMapper objectMapper;
    private final MailService mailService; // 寄信服務
    private final AppUrlProperties appUrlProperties; // 連結
    private final TurnstileService turnstileService;

    /** 一般帳密註冊（暫存 Redis 階段，不寫入 DB） */
    public void registerPending(
            String username, String email, String rawPassword, String turnstileToken) {
        // 1. 進行 Turnstile 人機驗證
        turnstileService.verifyToken(turnstileToken, null);

        // 一律轉成小寫並去空白
        String normalizedEmail = email.toLowerCase().trim();

        // 【第一道防線：60秒防重複發信鎖】
        // 建立一個專屬於這個 Email 的鎖 Key
        String rateLimitKey = "AUTH:LOCK:EMAIL:" + normalizedEmail;

        // 使用 SETNX (Set if Not Exists) 特性：如果 Key 不存在才寫入，並同步設定 60 秒過期
        // Boolean.TRUE 代表寫入成功（之前沒鎖）；FALSE 代表已存在（還在 60 秒冷卻內）
        Boolean isLockSuccess =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(rateLimitKey, "locked", 60, TimeUnit.SECONDS);

        // 如果 isLockSuccess 為 null 或 false，代表 60 秒內已經發過信了，直接攔截！
        if (isLockSuccess == null || !isLockSuccess) {
            // 丟出自訂的異常，例如：請勿頻繁發送驗證信，或 429 Too Many Requests
            throw new AuthException(AuthErrorCode.TOO_MANY_REQUESTS);
        }

        // 順利拿到鎖，代表這 60 秒他是合法的，繼續往下走原本的邏輯...

        // 【第二道防線：檢查 Email 是否已經在 DB 中存在】
        if (userRepository.existsByEmail(normalizedEmail)) {
            // 實務資安小優化：如果信箱已存在，雖然丟異常，但因為前面已經設了 60 秒鎖，
            // 壞人也沒辦法用這個已註冊的信箱來瘋狂刷你的郵件伺服器！
            throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        try {
            // 打包成 DTO 準備進 Redis
            PendingUserDto pendingUser =
                    PendingUserDto.builder()
                            .username(username)
                            .email(normalizedEmail)
                            .passwordHash(passwordEncoder.encode(rawPassword))
                            .build();

            String jsonStr = objectMapper.writeValueAsString(pendingUser);

            // 送進萬用驗證中心（效期 24 小時）
            String token = tokenService.createToken("VERIFY_EMAIL", jsonStr, 1440);

            // 輸出測試啟動連結
            // String activateLink = "http://localhost:8080/v1/auth/activate?token=" + token;
            // System.out.println("\n==================================================");
            // System.out.println("【Classcord 帳號啟用信】模擬寄出成功！");
            // System.out.println(activateLink);
            // System.out.println("==================================================\n");

            String activateLink =
                    appUrlProperties.getBackend() + "/v1/auth/activate?token=" + token;

            mailService.sendAuthMail(
                    normalizedEmail,
                    "【Classcord】請驗證您的電子郵件以開通帳號",
                    "REGISTER",
                    "Classcord",
                    "感謝您註冊 Classcord 雲端課堂平台！為了確保您的帳戶安全，請點擊下方按鈕以啟用帳戶：",
                    "啟用我的帳號",
                    activateLink,
                    "⏰ 本開通連結於 24 小時內有效，逾期需重新註冊。");

        } catch (JsonProcessingException e) {
            // 如果後續序列化失敗，為了避免使用者被卡死 60 秒，實務上可以把鎖手動刪除
            redisTemplate.delete(rateLimitKey);
            throw new RuntimeException("註冊資料處理異常，無法序列化", e);
        }
    }

    /** 使用者點擊驗證信連結（正式寫入 DB ） */
    @Transactional
    public void activateUser(String token) {
        // 1. 從驗證中心取出 JSON 字串（由底層 verifyAndConsume 確保沒過期，且用完立刻在 Redis 銷毀）
        String jsonStr = tokenService.verifyAndConsume("VERIFY_EMAIL", token);

        try {
            // 2. 反序列化回 Lombok DTO
            PendingUserDto pendingUser = objectMapper.readValue(jsonStr, PendingUserDto.class);

            // 3. 防禦性
            if (userRepository.existsByEmail(pendingUser.getEmail())) {
                throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
            }

            // Step 1: 建立並儲存主用戶表 (users)
            User newUser =
                    User.builder()
                            .username(pendingUser.getUsername())
                            .email(pendingUser.getEmail())
                            .build();
            userRepository.save(newUser);

            // Step 2: 建立本地憑證記錄 (user_identities)
            UserIdentity localIdentity =
                    UserIdentity.builder()
                            .user(newUser)
                            .provider(AuthProvider.LOCAL)
                            .providerUid(null)
                            .passwordHash(pendingUser.getPasswordHash()) // 直接拿當初加密好的密碼
                            .build();
            userIdentityRepository.save(localIdentity);

            log.info("🎉 帳號驗證成功！數據正式落地 DB，歡迎新成員：{}", pendingUser.getUsername());

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("開通帳號失敗，資料還原異常", e);
        }
    }

    /** 忘記密碼階段一：驗證信箱存在，並產生限時 15 分鐘的重設憑證 */
    public void sendResetPasswordLink(String email) {
        String normalizedEmail = email.toLowerCase().trim();

        // 【第一道防線：60 秒防刷原子鎖】
        // 避免惡意黑客利用忘記密碼端點，瘋狂幫某個可憐的使用者炸彈式寄信
        String rateLimitKey = "AUTH:LOCK:RESET:" + normalizedEmail;
        Boolean isLockSuccess =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(rateLimitKey, "locked", 60, TimeUnit.SECONDS);

        if (isLockSuccess == null || !isLockSuccess) {
            throw new AuthException(AuthErrorCode.TOO_MANY_REQUESTS);
        }

        // 【第二道防線：檢查真偽】只有真正註冊過的使用者才能重設密碼
        User user =
                userRepository
                        .findByEmail(normalizedEmail)
                        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        try {
            // 這裡不需要包成 DTO，因為重設密碼時，我們只需要在 Redis 記住「是哪一個 Email 發起重設的」即可
            // 呼叫你的萬用驗證中心，產生用途為 "RESET_PASSWORD" 的 Token，時效給 15 分鐘
            String token = tokenService.createToken("RESET_PASSWORD", normalizedEmail, 15);

            // // 輸出測試重設連結
            // String resetLink = "http://localhost:8080/v1/auth/reset-password?token=" + token;
            // System.out.println("\n==================================================");
            // System.out.println("【Classcord 密碼重設信】模擬寄出成功！");
            // System.out.println("請複製下方連結並帶上 newPassword 參數執行重設（15分鐘內有效）：");
            // System.out.println(resetLink);
            // System.out.println("==================================================\n");

            // 指向前端密碼重設路由
            String resetLink = appUrlProperties.getFrontend() + "/reset-password?token=" + token;

            mailService.sendAuthMail(
                    normalizedEmail,
                    "【Classcord】密碼重設驗證通知",
                    "RESET",
                    "Classcord",
                    "我們收到了您重設 Classcord 帳戶密碼的請求。請點擊下方按鈕以重新設定您的密碼：",
                    "重設我的密碼",
                    resetLink,
                    "⚠️ 本重設連結安全時效為 15 分鐘。若您並未發起此請求，請忽略本信件。");

        } catch (Exception e) {
            // 如果中途發生非預期異常，立刻手動移除 60 秒鎖，避免卡死正常使用者
            redisTemplate.delete(rateLimitKey);
            throw new RuntimeException("重設密碼憑證產生失敗", e);
        }
    }

    /** 忘記密碼階段二：持有效 Token 正式覆蓋舊密碼（落地 DB ） */
    @Transactional // 涉及修改 DB 資料，必須開啟事務
    public void executePasswordReset(String token, String rawNewPassword) {
        // 1. 去驗證中心核對 Token
        // （底層 verifyAndConsume 會自動去 Redis 取出當初存的 Email，且用完即刪，確保連結只能用一次！）
        String email = tokenService.verifyAndConsume("RESET_PASSWORD", token);

        // 2. 透過 Email 找到對應的主用戶
        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        // 3. 找到該用戶本地登入（local）的身份憑證
        UserIdentity localIdentity =
                userIdentityRepository
                        .findByUserAndProvider(user, AuthProvider.LOCAL)
                        .orElseThrow(
                                () -> new AuthException(AuthErrorCode.LOCAL_IDENTITY_NOT_FOUND));

        // 4. 將新密碼進行 Bcrypt 加鹽雜湊後覆蓋寫入
        String encodedNewPassword = passwordEncoder.encode(rawNewPassword);
        localIdentity.setPasswordHash(encodedNewPassword);

        // 5. 儲存變更（也可以不寫，Spring 事務結束會自動 Dirty Checking Flush，但寫了語意更明確）
        userIdentityRepository.save(localIdentity);

        log.info("🎉 使用者 {} 的密碼已順利重設成功！舊連結已自動失效。", user.getUsername());
    }

    // /** 一般帳密註冊 */
    // @Transactional
    // public void registerLocal(String username, String email, String rawPassword) {
    //
    //     // 一律轉成小寫
    //     String normalizedEmail = email.toLowerCase().trim();
    //
    //     // 檢查 Email 是否已經被註冊過
    //     if (userRepository.existsByEmail(normalizedEmail)) {
    //         // todo 改成寄「你已有帳號」的提醒信給該 Email
    //         throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    //     }
    //
    //     // Step 1: 建立並儲存主用戶表 (users)
    //     User newUser = User.builder().username(username).email(normalizedEmail).build();
    //     userRepository.save(newUser);
    //
    //     // Step 2: 密碼加鹽加密
    //     String encodedPassword = passwordEncoder.encode(rawPassword);
    //
    //     // Step 3: 建立本地憑證記錄 (user_identities)
    //     UserIdentity localIdentity =
    //             UserIdentity.builder()
    //                     .user(newUser)
    //                     .provider(AuthProvider.LOCAL) // 標記為本地登入
    //                     .providerUid(null) // 本地登入沒有第三方 UID
    //                     .passwordHash(encodedPassword) // 塞入加密後的密碼
    //                     .build();
    //     userIdentityRepository.save(localIdentity);
    // }

    /** 一般帳密登入 (驗證成功就發行 JWT 門票) */
    public String loginLocal(String email, String rawPassword, String turnstileToken) {
        // 1. 進行 Turnstile 人機驗證
        turnstileService.verifyToken(turnstileToken, null);

        // 一律轉成小寫
        String normalizedEmail = email.toLowerCase().trim();

        // todo 防止時序攻擊、Rate Limiting
        // Step 1: 透過 Email 找到主用戶
        User user =
                userRepository
                        .findByEmail(normalizedEmail)
                        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        // Step 2: 透過 user_id 和 provider='local' 找到密碼憑證
        UserIdentity localIdentity =
                userIdentityRepository
                        .findByUserAndProvider(user, AuthProvider.LOCAL)
                        .orElseThrow(
                                () -> new AuthException(AuthErrorCode.LOCAL_IDENTITY_NOT_FOUND));

        // Step 3: 比對明文密碼與資料庫的密碼雜湊值是否吻合
        if (!passwordEncoder.matches(rawPassword, localIdentity.getPasswordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        // Step 4: 驗證成功！大方呼叫你的大管家簽發門票
        return jwtUtils.generateToken(user.getId(), user.getEmail());
    }

    /** * 一般帳密登出 (將 Token 送進 Redis 黑名單，並加上防時間差緩衝) */
    public void logoutLocal(String token) {
        if (token == null) {
            return;
        }

        // 1. 呼叫 JwtUtils 的方法，算出「殘餘秒數 + 2分鐘緩衝」
        long remainingSeconds = jwtUtils.getRemainingTimeInSeconds(token);

        // 2. 如果 remainingSeconds 大於 0，代表這張票在 Java 這邊還沒過期，必須加進 Redis
        if (remainingSeconds > 0) {

            // 加上統一的 Key 前綴（Prefix），方便在 Redis 中分類與管理
            String redisKey = "JWT_BLACKLIST:" + token;

            // 3. 寫入 Redis：Key 是 Token，Value 放字串（標記用）
            // 傳入算好的過期時間，時間到了 Redis 會自動在記憶體裡把它銷毀
            redisTemplate
                    .opsForValue()
                    .setIfAbsent(redisKey, "revoked", remainingSeconds, TimeUnit.SECONDS);
        }
    }
}
