package com.hys.classcord.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hys.classcord.auth.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;

// 純單元測試：直接測試 WebSocketConfig 裡註冊的 STOMP CONNECT 認證攔截器，
// 不啟動 Spring context、不建立真的 WebSocket 連線。
@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private TaskScheduler taskScheduler;

    private ChannelInterceptor authInterceptor;

    @BeforeEach
    void setUp() {
        WebSocketConfig webSocketConfig =
                new WebSocketConfig(jwtUtils, redisTemplate, taskScheduler);

        ChannelRegistration registration = mock(ChannelRegistration.class);
        webSocketConfig.configureClientInboundChannel(registration);

        ArgumentCaptor<ChannelInterceptor> captor =
                ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        authInterceptor = captor.getValue();
    }

    private Message<byte[]> connectMessageWithHeader(String authorizationHeaderValue) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorizationHeaderValue != null) {
            accessor.setNativeHeader("Authorization", authorizationHeaderValue);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private MessageChannel dummyChannel() {
        return mock(MessageChannel.class);
    }

    // 沒有帶 Authorization Header 的 CONNECT 請求，應該直接拒絕連線
    @Test
    void preSend_rejectsConnect_whenAuthorizationHeaderMissing() {
        Message<byte[]> message = connectMessageWithHeader(null);

        assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel()))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // Authorization Header 存在但不是「Bearer 」開頭，格式不對一樣要拒絕
    @Test
    void preSend_rejectsConnect_whenAuthorizationHeaderIsNotBearerFormat() {
        Message<byte[]> message = connectMessageWithHeader("Basic dXNlcjpwYXNz");

        assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel()))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // Token 已經被列入 JWT 黑名單（例如使用者已登出），應該拒絕連線，即使 Token 本身簽章正確
    @Test
    void preSend_rejectsConnect_whenTokenIsBlacklisted() {
        Message<byte[]> message = connectMessageWithHeader("Bearer revoked-token");
        when(redisTemplate.hasKey("JWT_BLACKLIST:revoked-token")).thenReturn(true);

        assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel()))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // Token 沒有被列入黑名單，但簽章或內容無效（JwtUtils 解析不出使用者 ID），應該拒絕連線
    @Test
    void preSend_rejectsConnect_whenTokenIsInvalid() {
        Message<byte[]> message = connectMessageWithHeader("Bearer invalid-token");
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtils.getUserIdFromToken("invalid-token")).thenReturn(null);

        assertThatThrownBy(() -> authInterceptor.preSend(message, dummyChannel()))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // Token 合法且未被列入黑名單，應該放行並把使用者身分綁定到這個 STOMP Session 上
    @Test
    void preSend_allowsConnect_andBindsUser_whenTokenIsValid() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer valid-token");
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(redisTemplate.hasKey("JWT_BLACKLIST:valid-token")).thenReturn(false);
        when(jwtUtils.getUserIdFromToken("valid-token")).thenReturn("user-123");

        Message<?> result = authInterceptor.preSend(message, dummyChannel());

        assertThat(result).isNotNull();
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("user-123");
    }

    // 非 CONNECT 命令（例如平常發送訊息的 SEND）不需要重新驗證，應該直接放行
    @Test
    void preSend_skipsAuthCheck_forNonConnectCommands() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = authInterceptor.preSend(message, dummyChannel());

        assertThat(result).isSameAs(message);
    }
}
