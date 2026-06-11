package com.hys.classcord.auth.dto;

import com.hys.classcord.auth.enums.AuthProvider;

/** 統一的第三方用戶資訊包 */
public record OAuthUserInfoDto(
        String providerUserId, // 第三方平台給的唯一 ID (e.g., Google 的 sub, GitHub 的 id)
        String email,          // 用戶在該平台的信箱
        String username,       // 用戶在該平台的暱稱
        String avatarUrl,      // 用戶在該平台的頭像
        AuthProvider provider,  // 來源平台 (GOOGLE, GITHUB, DISCORD)
        boolean isEmailVerified // 該平台的 Email 是否通過驗證
) {}
