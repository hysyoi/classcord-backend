package com.hys.classcord.core.config;

import com.hys.classcord.auth.security.JwtUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;

    @Value("#{\"${app.cors.allowed-origins}\".split(\"\\s*,\\s*\")}")
    private List<String> allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 訂閱廣播目的地的前綴
        config.enableSimpleBroker("/topic", "/queue");
        // 前端發送訊息的目的地前綴
        config.setApplicationDestinationPrefixes("/app");
        // 用於使用者個別訂閱的對象路徑前綴
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 註冊 "/ws" 連線端點，允許全來源 Origin Patterns (*) 通過網關握手
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new ChannelInterceptor() {
                    @Override
                    public Message<?> preSend(
                            @NonNull Message<?> message, @NonNull MessageChannel channel) {
                        StompHeaderAccessor accessor =
                                MessageHeaderAccessor.getAccessor(
                                        message, StompHeaderAccessor.class);

                        if (accessor != null
                                && StompCommand.CONNECT.equals(accessor.getCommand())) {
                            String authHeader = accessor.getFirstNativeHeader("Authorization");
                            log.debug("WebSocket CONNECT 認證檢查，Authorization: {}", authHeader);

                            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                                String token = authHeader.substring(7);

                                // 檢查 Redis 黑名單
                                String redisKey = "JWT_BLACKLIST:" + token;
                                if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                                    log.warn("WebSocket 連線認證失敗：JWT Token 已被作廢（黑名單）");
                                    throw new MessageDeliveryException("認證已失效，請重新登入");
                                }

                                String userId = jwtUtils.getUserIdFromToken(token);

                                if (userId != null) {
                                    // 身分認證通過，將使用者綁定至 STOMP Session
                                    UsernamePasswordAuthenticationToken auth =
                                            new UsernamePasswordAuthenticationToken(
                                                    userId,
                                                    null,
                                                    List.of(
                                                            new SimpleGrantedAuthority(
                                                                    "ROLE_USER")));
                                    accessor.setUser(auth);
                                    log.info("WebSocket 連線認證成功，使用者 ID: {}", userId);
                                } else {
                                    log.warn("WebSocket 連線認證失敗：JWT Token 無效或已過期");
                                    throw new MessageDeliveryException("無效的認證憑證");
                                }
                            } else {
                                log.warn("WebSocket 連線認證失敗：未提供 Authorization Header 或格式不符");
                                throw new MessageDeliveryException("缺少認證憑證");
                            }
                        }
                        return message;
                    }
                });
    }
}
