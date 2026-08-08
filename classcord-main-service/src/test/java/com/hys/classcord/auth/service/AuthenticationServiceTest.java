package com.hys.classcord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

// 純單元測試：不啟動 Spring context、不連真的 Redis/DB，全部依賴用 Mockito 替身取代。
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserIdentityRepository userIdentityRepository;
    @Mock private JwtUtils jwtUtils;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private VerificationTokenService tokenService;
    @Mock private MailService mailService;
    @Mock private TurnstileService turnstileService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppUrlProperties appUrlProperties = new AppUrlProperties();

    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        appUrlProperties.setFrontend("http://localhost:3000");
        appUrlProperties.setBackend("http://localhost:8080");

        authenticationService =
                new AuthenticationService(
                        userRepository,
                        userIdentityRepository,
                        jwtUtils,
                        passwordEncoder,
                        redisTemplate,
                        tokenService,
                        objectMapper,
                        mailService,
                        appUrlProperties,
                        turnstileService);
    }

    private User buildUser(String email) {
        User user = User.builder().username("TestUser").email(email).build();
        user.setId(UUID.randomUUID());
        return user;
    }

    // ==========================================
    // loginLocal
    // ==========================================

    // 帳號密碼都正確，且 Email 有大小寫與空白，應該正規化後才查詢，並成功發出 JWT
    @Test
    void loginLocal_returnsJwt_whenCredentialsAreValid() {
        User user = buildUser("test@example.com");
        UserIdentity identity =
                UserIdentity.builder()
                        .user(user)
                        .provider(AuthProvider.LOCAL)
                        .passwordHash("hashed-password")
                        .build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserAndProvider(user, AuthProvider.LOCAL))
                .thenReturn(Optional.of(identity));
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(true);
        when(jwtUtils.generateToken(user.getId(), user.getEmail())).thenReturn("mock-jwt-token");

        String jwt =
                authenticationService.loginLocal(
                        "  Test@Example.COM  ", "Password123!", "turnstile-token");

        assertThat(jwt).isEqualTo("mock-jwt-token");
        // Email 應該被正規化（轉小寫、去空白）後才拿去查詢，而不是原始輸入
        verify(userRepository).findByEmail("test@example.com");
    }

    // 帳號不存在，應該丟出「帳號或密碼錯誤」，而不是洩漏「帳號不存在」這種可被用來枚舉帳號的細節
    @Test
    void loginLocal_throwsInvalidCredentials_whenUserNotFound() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authenticationService.loginLocal(
                                        "nobody@example.com", "anyPassword", "turnstile-token"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.getCode());
    }

    // 帳號存在，但沒有本地密碼憑證（例如純第三方登入帳號），應該提示改用其他登入方式
    @Test
    void loginLocal_throwsLocalIdentityNotFound_whenUserHasNoLocalCredential() {
        User user = buildUser("oauth-only@example.com");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserAndProvider(user, AuthProvider.LOCAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authenticationService.loginLocal(
                                        "oauth-only@example.com", "anyPassword", "turnstile-token"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.LOCAL_IDENTITY_NOT_FOUND.getCode());
    }

    // 密碼不吻合，應該丟出「帳號或密碼錯誤」
    @Test
    void loginLocal_throwsInvalidCredentials_whenPasswordDoesNotMatch() {
        User user = buildUser("test@example.com");
        UserIdentity identity =
                UserIdentity.builder()
                        .user(user)
                        .provider(AuthProvider.LOCAL)
                        .passwordHash("hashed-password")
                        .build();
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserAndProvider(user, AuthProvider.LOCAL))
                .thenReturn(Optional.of(identity));
        when(passwordEncoder.matches(anyString(), eq("hashed-password"))).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                authenticationService.loginLocal(
                                        "test@example.com", "wrongPassword", "turnstile-token"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.getCode());
    }

    // ==========================================
    // logoutLocal
    // ==========================================

    // token 為 null（例如未帶 Authorization header 就呼叫登出）應該直接跳過，不碰 JWT 解析或 Redis
    @Test
    void logoutLocal_doesNothing_whenTokenIsNull() {
        authenticationService.logoutLocal(null);

        verifyNoInteractions(jwtUtils);
        verifyNoInteractions(redisTemplate);
    }

    // Token 還沒過期，應該把它連同緩衝時間一起寫進 Redis 黑名單
    @Test
    void logoutLocal_addsTokenToBlacklist_whenTokenNotYetExpired() {
        String token = "valid-jwt-token";
        when(jwtUtils.getRemainingTimeInSeconds(token)).thenReturn(500L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authenticationService.logoutLocal(token);

        verify(valueOperations)
                .setIfAbsent("JWT_BLACKLIST:" + token, "revoked", 500L, TimeUnit.SECONDS);
    }

    // Token 本身已經自然過期，就不需要再多寫一筆黑名單資料（反正驗證時本來就會被拒絕）
    @Test
    void logoutLocal_doesNotWriteToRedis_whenTokenAlreadyExpired() {
        String token = "expired-jwt-token";
        when(jwtUtils.getRemainingTimeInSeconds(token)).thenReturn(0L);

        authenticationService.logoutLocal(token);

        verifyNoInteractions(redisTemplate);
    }

    // ==========================================
    // sendResetPasswordLink
    // ==========================================

    // 60 秒防刷鎖還在冷卻中，應該直接擋下，不查資料庫也不寄信
    @Test
    void sendResetPasswordLink_throwsTooManyRequests_whenRateLimited() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                        eq("AUTH:LOCK:RESET:test@example.com"),
                        anyString(),
                        anyLong(),
                        eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(
                        () ->
                                authenticationService.sendResetPasswordLink(
                                        "test@example.com", "turnstile-token"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.TOO_MANY_REQUESTS.getCode());

        verifyNoInteractions(userRepository);
    }

    // Email 確實有註冊，應該正規化 Email、產生重設 Token 並寄出重設信
    @Test
    void sendResetPasswordLink_sendsResetEmail_whenEmailIsRegistered() {
        User user = buildUser("user@example.com");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.createToken("RESET_PASSWORD", "user@example.com", 15))
                .thenReturn("reset-token-abc");

        authenticationService.sendResetPasswordLink("  User@Example.COM  ", "turnstile-token");

        verify(tokenService).createToken("RESET_PASSWORD", "user@example.com", 15);
        verify(mailService)
                .sendAuthMail(
                        eq("user@example.com"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("http://localhost:3000/reset-password?token=reset-token-abc"),
                        anyString());
    }

    // Email 沒註冊過，基於防止帳號枚舉攻擊的考量，應該靜默忽略——不寄信、不產生 Token，
    // 也不能讓呼叫端看出「這封信到底寄出去了沒」
    @Test
    void sendResetPasswordLink_silentlyIgnores_whenEmailIsNotRegistered() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        authenticationService.sendResetPasswordLink("nobody@example.com", "turnstile-token");

        verifyNoInteractions(tokenService);
        verifyNoInteractions(mailService);
    }

    // ==========================================
    // registerPending
    // ==========================================

    // 60 秒防刷鎖還在冷卻中，應該直接擋下，不查資料庫也不寄信
    @Test
    void registerPending_throwsTooManyRequests_whenRateLimited() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                        eq("AUTH:LOCK:EMAIL:test@example.com"),
                        anyString(),
                        anyLong(),
                        eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(
                        () ->
                                authenticationService.registerPending(
                                        "TestUser",
                                        "test@example.com",
                                        "Password123!",
                                        "turnstile-token"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.TOO_MANY_REQUESTS.getCode());

        verifyNoInteractions(userRepository);
        verifyNoInteractions(mailService);
    }

    // Email 已經註冊過，基於防列舉考量不直接報錯，改寄「帳戶已存在」提醒信，且不產生開通 Token
    @Test
    void registerPending_sendsAlreadyRegisteredNotice_whenEmailAlreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        authenticationService.registerPending(
                "TestUser", "test@example.com", "Password123!", "turnstile-token");

        verify(mailService)
                .sendAuthMail(
                        eq("test@example.com"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("http://localhost:3000/login"),
                        anyString());
        verifyNoInteractions(tokenService);
    }

    // 全新 Email，應該把密碼加密後暫存進 Redis Token，並寄出開通信
    @Test
    void registerPending_createsVerificationTokenAndSendsMail_whenEmailIsNew() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed-password");
        when(tokenService.createToken(eq("VERIFY_EMAIL"), anyString(), eq(1440L)))
                .thenReturn("verify-token-abc");

        authenticationService.registerPending(
                "NewUser", "  New@Example.COM  ", "Password123!", "turnstile-token");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenService).createToken(eq("VERIFY_EMAIL"), jsonCaptor.capture(), eq(1440L));
        PendingUserDto pendingUser =
                objectMapper.readValue(jsonCaptor.getValue(), PendingUserDto.class);
        assertThat(pendingUser.getUsername()).isEqualTo("NewUser");
        assertThat(pendingUser.getEmail()).isEqualTo("new@example.com");
        assertThat(pendingUser.getPasswordHash()).isEqualTo("hashed-password");

        verify(mailService)
                .sendAuthMail(
                        eq("new@example.com"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("http://localhost:8080/v1/auth/activate?token=verify-token-abc"),
                        anyString());
    }

    // 寄信/序列化中途失敗時，應該把先前拿到的 60 秒防刷鎖釋放掉，避免使用者被卡死無法重新註冊
    @Test
    void registerPending_releasesRateLimitLock_whenSendingMailFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(tokenService.createToken(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("Redis 掛了"));

        assertThatThrownBy(
                        () ->
                                authenticationService.registerPending(
                                        "TestUser",
                                        "test@example.com",
                                        "Password123!",
                                        "turnstile-token"))
                .isInstanceOf(RuntimeException.class);

        verify(redisTemplate).delete("AUTH:LOCK:EMAIL:test@example.com");
    }

    // ==========================================
    // activateUser
    // ==========================================

    // Token 有效、Email 尚未被註冊過，應該正式寫入 User 與本地登入憑證，並銷毀已使用的 Token
    @Test
    void activateUser_createsUserAndConsumesToken_whenTokenIsValid() throws Exception {
        PendingUserDto pendingUser =
                PendingUserDto.builder()
                        .username("NewUser")
                        .email("new@example.com")
                        .passwordHash("hashed-password")
                        .build();
        String jsonStr = objectMapper.writeValueAsString(pendingUser);
        when(tokenService.verify("VERIFY_EMAIL", "verify-token-abc")).thenReturn(jsonStr);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        authenticationService.activateUser("verify-token-abc");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("NewUser");
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");

        ArgumentCaptor<UserIdentity> identityCaptor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(identityCaptor.capture());
        assertThat(identityCaptor.getValue().getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(identityCaptor.getValue().getPasswordHash()).isEqualTo("hashed-password");

        // 測試環境沒有真正的交易在跑，isSynchronizationActive() 會是 false，
        // 所以會直接走「立即銷毀 Token」這條路徑，而不是註冊 afterCommit 回呼
        verify(tokenService).consume("VERIFY_EMAIL", "verify-token-abc");
    }

    // 這個 Email 中途已經被別的請求搶先註冊掉了，應該拒絕重複建立帳號
    @Test
    void activateUser_throwsEmailAlreadyExists_whenEmailWasRegisteredInTheMeantime()
            throws Exception {
        PendingUserDto pendingUser =
                PendingUserDto.builder()
                        .username("NewUser")
                        .email("taken@example.com")
                        .passwordHash("hashed-password")
                        .build();
        String jsonStr = objectMapper.writeValueAsString(pendingUser);
        when(tokenService.verify("VERIFY_EMAIL", "verify-token-abc")).thenReturn(jsonStr);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authenticationService.activateUser("verify-token-abc"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS.getCode());

        verifyNoInteractions(userIdentityRepository);
        verify(tokenService, never()).consume(anyString(), anyString());
    }

    // ==========================================
    // executePasswordReset
    // ==========================================

    // Token 有效，應該把新密碼加密後覆蓋舊憑證，並銷毀重設用的 Token
    @Test
    void executePasswordReset_updatesPasswordAndConsumesToken_whenTokenIsValid() {
        User user = buildUser("user@example.com");
        UserIdentity identity =
                UserIdentity.builder()
                        .user(user)
                        .provider(AuthProvider.LOCAL)
                        .passwordHash("old-hash")
                        .build();
        when(tokenService.verify("RESET_PASSWORD", "reset-token-abc"))
                .thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserAndProvider(user, AuthProvider.LOCAL))
                .thenReturn(Optional.of(identity));
        when(passwordEncoder.encode("NewPassword456!")).thenReturn("new-hash");

        authenticationService.executePasswordReset("reset-token-abc", "NewPassword456!");

        assertThat(identity.getPasswordHash()).isEqualTo("new-hash");
        verify(userIdentityRepository).save(identity);
        verify(tokenService).consume("RESET_PASSWORD", "reset-token-abc");
    }

    // Token 有效但對應的使用者已經不存在（例如帳號被刪除），應該視為無效憑證
    @Test
    void executePasswordReset_throwsInvalidCredentials_whenUserNotFound() {
        when(tokenService.verify("RESET_PASSWORD", "reset-token-abc"))
                .thenReturn("ghost@example.com");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authenticationService.executePasswordReset(
                                        "reset-token-abc", "NewPassword456!"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.getCode());
    }

    // 使用者存在，但沒有本地密碼憑證（純第三方登入帳號），不該允許透過這條路徑設定密碼
    @Test
    void executePasswordReset_throwsLocalIdentityNotFound_whenUserHasNoLocalCredential() {
        User user = buildUser("oauth-only@example.com");
        when(tokenService.verify("RESET_PASSWORD", "reset-token-abc"))
                .thenReturn("oauth-only@example.com");
        when(userRepository.findByEmail("oauth-only@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserAndProvider(user, AuthProvider.LOCAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authenticationService.executePasswordReset(
                                        "reset-token-abc", "NewPassword456!"))
                .isInstanceOf(AuthException.class)
                .extracting("code")
                .isEqualTo(AuthErrorCode.LOCAL_IDENTITY_NOT_FOUND.getCode());
    }
}
