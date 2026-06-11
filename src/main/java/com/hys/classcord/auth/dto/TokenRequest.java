package com.hys.classcord.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 接收前端第三方登入時發送的 Token 載體
 * @param credential Google 傳的是 id_token；GitHub/Discord 傳的是 authorization code
 */
public record TokenRequest(
        @NotBlank(message = "第三方驗證憑證不能為空")
        String credential
) {
}