package com.hys.classcord.message.controller;

import com.hys.classcord.message.dto.CreateMessageRequest;
import com.hys.classcord.message.dto.MessageResponse;
import com.hys.classcord.message.dto.WsMessageRequest;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.service.MessageService;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 處理前端發送的實時聊天訊息，並廣播給該班級內的所有成員 前端發送目的地：/app/servers/{serverId}/chat
     * 線上成員訂閱：/topic/servers/{serverId}/messages
     */
    @MessageMapping("/servers/{serverId}/chat")
    public void handleChatMessage(
            @DestinationVariable UUID serverId, WsMessageRequest request, Principal principal) {

        if (principal == null) {
            log.warn("WebSocket 訊息發送被拒絕：未授權的連線");
            return;
        }

        try {
            // 從 Principal (在 WebSocketConfig 中綁定) 獲取使用者 ID
            UUID userId = UUID.fromString(principal.getName());
            log.info(
                    "接收到實時訊息 -> 伺服器: {}, 使用者: {}, 頻道: {}, 內容: {}",
                    serverId,
                    userId,
                    request.channelId(),
                    request.content());

            CreateMessageRequest serviceRequest = new CreateMessageRequest(request.content());

            // 1. 調用服務儲存訊息至資料庫中 (此方法內會驗證該頻道是否屬於該伺服器、及該使用者的發言權限)
            Message message =
                    messageService.sendMessage(userId, request.channelId(), serviceRequest);

            // 2. 轉換為統一的 DTO 回應
            MessageResponse response = MessageResponse.fromEntity(message);

            // 3. 廣播給訂閱了該班級訊息流的所有客戶端
            messagingTemplate.convertAndSend("/topic/servers/" + serverId + "/messages", response);

        } catch (Exception e) {
            log.error("處理實時聊天訊息發生錯誤：", e);
        }
    }
}
