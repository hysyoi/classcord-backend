package com.hys.classcord.auth.service;

import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.entity.UserIdentity;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.auth.exception.AuthException;
import com.hys.classcord.auth.repository.UserIdentityRepository;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.auth.service.oauth.OAuth2Strategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuth2AuthService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final JwtUtils javaJwtUtils;
    private final TransactionTemplate transactionTemplate;
    private final Map<AuthProvider, OAuth2Strategy> strategies = new ConcurrentHashMap<>();

    // Spring 會自動把所有實作了 OAuth2Strategy 的 Bean 注入進來
    public OAuth2AuthService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            JwtUtils javaJwtUtils,
            TransactionTemplate transactionTemplate,
            List<OAuth2Strategy> strategyList) {

        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.javaJwtUtils = javaJwtUtils;
        this.transactionTemplate = transactionTemplate;

        // 將策略依據 Provider 分類存入 Map 中
        strategyList.forEach(strategy -> strategies.put(strategy.getProvider(), strategy));
    }

    public String handleOAuthLogin(AuthProvider provider, String token) {
        // 1. 根據平台動態取得對應的驗證策略
        OAuth2Strategy strategy =
                Optional.ofNullable(strategies.get(provider))
                        .orElseThrow(
                                () ->
                                        new AuthException(
                                                AuthErrorCode.UNSUPPORTED_PROVIDER,
                                                "未支援的第三方平台: " + provider));

        // 2. 驗證並拿到統一格式的用戶資料 (慢速第三方 API 網路驗證，在交易外執行以釋放連線)
        OAuthUserInfoDto userInfo = strategy.verifyAndExtractInfo(token);

        // 3. 在短交易中處理登入與註冊寫庫邏輯 (使用 TransactionTemplate 避開 self-invocation 交易失效問題)
        UserIdentity identity =
                transactionTemplate.execute(
                        status ->
                                userIdentityRepository
                                        .findByProviderAndProviderUid(
                                                userInfo.provider(), userInfo.providerUserId())
                                        .orElseGet(() -> registerNewOAuthUser(userInfo)));

        // 4. 發放 Classcord 通行證
        return javaJwtUtils.generateToken(
                identity.getUser().getId(), identity.getUser().getEmail());
    }

    private UserIdentity registerNewOAuthUser(OAuthUserInfoDto userInfo) {
        // 1. 檢查核心 users 表，拿到 Optional 包裝的使用者主體
        Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

        // 資安保護：如果要引發帳號融合（existingUser 存在），但此平台的 Email 未經過驗證
        if (existingUser.isPresent() && !userInfo.isEmailVerified()) {
            throw new AuthException(
                    AuthErrorCode.OAUTH_EMAIL_UNVERIFIED, "資安保護：不允許使用未驗證該 Email 的第三方平台進行帳號融合");
        }

        // 2. 直接用上面拿到的 existingUser，不用再查一次資料庫
        User user =
                existingUser.orElseGet(
                        () ->
                                userRepository.save(
                                        User.builder()
                                                .email(userInfo.email())
                                                .username(userInfo.username())
                                                .avatarUrl(userInfo.avatarUrl())
                                                .build()));

        // 3. 建立新憑證紀錄並直接回傳
        return userIdentityRepository.save(
                UserIdentity.builder()
                        .user(user)
                        .provider(userInfo.provider())
                        .providerUid(userInfo.providerUserId())
                        .build());
    }
}
