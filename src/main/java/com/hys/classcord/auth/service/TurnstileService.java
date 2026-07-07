package com.hys.classcord.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.auth.config.TurnstileProperties;
import com.hys.classcord.auth.enums.AuthErrorCode;
import com.hys.classcord.auth.exception.AuthException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class TurnstileService {

    private final TurnstileProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public TurnstileService(TurnstileProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // 業界最佳實踐：對外部 API 呼叫必須設定連線與讀取逾時 (3秒)
        // 避免 Cloudflare 服務異常時掛起 Spring Boot 的執行緒，造成執行緒池耗盡 (Thread Exhaustion)
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(3000);

        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public void verifyToken(String token, String clientIp) {
        // 如果在設定檔中把驗證關閉了，直接放行
        if (!properties.isEnabled()) {
            log.info("Turnstile 人機驗證已被停用，自動跳過驗證流程。");
            return;
        }

        if (token == null || token.isBlank()) {
            throw new AuthException(AuthErrorCode.INVALID_CAPTCHA, "人機驗證 Token 不可為空");
        }

        try {
            // 建構 Form Data 參數
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("secret", properties.getSecretKey());
            formData.add("response", token);
            if (clientIp != null) {
                formData.add("remoteip", clientIp);
            }

            // 發送 POST 請求，先讀取為原始字串防堵空回應
            // 💡 業界最佳實踐：使用專屬的 User-Agent 標頭（而非假冒瀏覽器 UA 以免造成 TLS/JA3 指紋不匹配被封鎖）
            // 雲端 WAF (如 Cloudflare) 通常只會封鎖預設/空白的 Java User-Agent，定義應用名稱即可安全通關
            String responseText =
                    restClient
                            .post()
                            .uri(VERIFY_URL)
                            .header(
                                    "User-Agent",
                                    "ClassCord-Backend/1.0.0 (Spring Boot RestClient)")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(formData)
                            .retrieve()
                            .body(String.class);

            log.debug("Cloudflare Turnstile 原始回應內容: {}", responseText);

            if (responseText == null || responseText.isBlank()) {
                log.warn("Cloudflare Turnstile 回傳了空的回應內容");
                throw new AuthException(AuthErrorCode.INVALID_CAPTCHA, "人機驗證連線異常，請重試");
            }

            Map<String, Object> response = objectMapper.readValue(responseText, Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                log.warn("Cloudflare Turnstile 驗證失敗。回應內容: {}", response);
                throw new AuthException(AuthErrorCode.INVALID_CAPTCHA, "人機驗證失敗，請重試");
            }

            log.info("Cloudflare Turnstile 驗證成功！");

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("調用 Cloudflare Turnstile API 時發生未預期異常: ", e);
            throw new AuthException(AuthErrorCode.INVALID_CAPTCHA, "驗證伺服器連線異常，請稍後再試");
        }
    }
}
