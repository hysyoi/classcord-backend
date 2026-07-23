package com.hys.classcord.auth.service.oauth;

import com.hys.classcord.auth.dto.OAuthUserInfoDto;
import com.hys.classcord.auth.enums.AuthProvider;

public interface OAuth2Strategy {
    /** 宣告自己支援哪一個第三方平台 */
    AuthProvider getProvider();

    /** 負責把前端傳來的憑證(Token/Code) 拿去該平台驗證，並解出統一的用戶資料 */
    OAuthUserInfoDto verifyAndExtractInfo(String token);
}
