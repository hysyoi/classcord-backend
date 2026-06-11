package com.hys.classcord.auth.controller;

import com.hys.classcord.auth.dto.LoginRequest;
import com.hys.classcord.auth.dto.LoginResponse;
import com.hys.classcord.auth.dto.RegisterRequest;
import com.hys.classcord.auth.dto.TokenRequest;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.service.AuthenticationService;
import com.hys.classcord.auth.service.OAuth2AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "帳戶模組 (Auth)", description = "處理使用者註冊、開通、登入、登出、密碼重設等 API")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final OAuth2AuthService oAuth2AuthService;

    /** 階段一：一般帳密註冊（暫存 Redis） */
    @PostMapping("/register")
    @Operation(
            summary = "一般帳密註冊 (暫存 Redis)",
            description = "輸入註冊資訊，後端會暫存至 Redis 並寄送開通連結。狀態碼回傳 202 Accepted。")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {

        authenticationService.registerPending(
                request.username(), request.email(), request.password());

        // 202 Accepted 代表已接受請求但尚未真正建立資料庫資源
        return ResponseEntity.accepted().build();
    }

    /** 階段二：使用者點擊驗證信連結（正式寫入 DB） */
    @GetMapping("/activate")
    @Operation(summary = "帳號啟用開通 (落地 DB)", description = "帶入信件中的 Token，核對無誤後資料正式落地。")
    public ResponseEntity<Void> activate(@RequestParam String token) {

        authenticationService.activateUser(token);

        return ResponseEntity.ok().build();
    }

    /** 忘記密碼階段一：輸入信箱，發送重設憑證 */
    @PostMapping("/forgot-password")
    @Operation(summary = "忘記密碼 (發送重設連結)", description = "輸入註冊信箱，若帳號存在則在主控台印出限時 15 分鐘的密碼重設連結。")
    public ResponseEntity<Void> forgotPassword(@RequestParam String email) {
        // 呼叫忘記密碼第一階段：校驗並產生 15 分鐘憑證
        authenticationService.sendResetPasswordLink(email);
        return ResponseEntity.accepted().build();
    }

    /** 忘記密碼階段二：提交新密碼與 Token 執行變更 */
    @PostMapping("/reset-password")
    @Operation(summary = "執行密碼重設", description = "傳入信箱連結中的 Token 以及你想設定的新密碼，核對成功即覆蓋舊密碼（僅限本地用戶）。")
    public ResponseEntity<Void> resetPassword(
            @RequestParam String token, @RequestParam String newPassword) {
        // 呼叫忘記密碼第二階段：持憑證正式覆蓋密碼
        authenticationService.executePasswordReset(token, newPassword);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    @Operation(summary = "一般帳密登入", description = "驗證成功就發行 JWT 門票。")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {

        String token = authenticationService.loginLocal(request.email(), request.password());

        return ResponseEntity.ok(new LoginResponse(token));
    }

    /** 使用者登出 */
    @PostMapping("/logout")
    @Operation(summary = "使用者登出", description = "將現有 Token 送進 Redis 黑名單封印。")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        // 1. 從前端的 HTTP Header 之中拔出 "Authorization"
        String headerAuth = request.getHeader("Authorization");
        String token = null;

        // 2. 檢查格式是否為 Bearer xxxxx
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            token = headerAuth.substring(7);
        }

        // 3. 遞交給 Service 執行 Redis 黑名單邏輯
        authenticationService.logoutLocal(token);

        // 4. 回傳 204 No Content，代表後端處理成功，且不需要回傳額外的 JSON 內容
        return ResponseEntity.noContent().build();
    }

    /**
     * 統一的第三方登入/註冊接口
     *
     * @param provider 路徑參數 (e.g., GOOGLE, GITHUB, DISCORD)
     * @param request 內含前端拿到的 idToken 或 Code
     */
    @PostMapping("/oauth/{provider}")
    public ResponseEntity<LoginResponse> oauthAuthenticate(
            @PathVariable AuthProvider provider, @Valid @RequestBody TokenRequest request) {

        // 呼叫大總管，自動根據 provider 分流處理，最後拿到 Classcord 內部 JWT
        String jwtToken = oAuth2AuthService.handleOAuthLogin(provider, request.credential());

        return ResponseEntity.ok(new LoginResponse(jwtToken));
    }
}
