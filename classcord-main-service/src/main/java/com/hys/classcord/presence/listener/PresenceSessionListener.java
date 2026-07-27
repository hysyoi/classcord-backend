package com.hys.classcord.presence.listener;

import com.hys.classcord.presence.service.PresenceService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** 監聽 STOMP Session 的連線/斷線事件，將使用者身分（於 WebSocketConfig 的 JWT 攔截器中綁定）交給 PresenceService 更新在線狀態。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceSessionListener {

    private final PresenceService presenceService;

    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        String userId = resolveUserId(event.getUser());
        if (userId != null) {
            presenceService.handleConnect(userId);
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String userId = resolveUserId(event.getUser());
        if (userId != null) {
            presenceService.handleDisconnect(userId);
        }
    }

    private String resolveUserId(Principal principal) {
        return principal != null ? principal.getName() : null;
    }
}
