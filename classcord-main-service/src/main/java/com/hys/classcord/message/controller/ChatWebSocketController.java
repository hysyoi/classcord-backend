package com.hys.classcord.message.controller;

import com.hys.classcord.message.dto.CreateMessageRequest;
import com.hys.classcord.message.dto.MessageResponse;
import com.hys.classcord.message.dto.WsMessageRequest;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

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

        Timer.Sample sample = Timer.start(meterRegistry);
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

            // 驗證通道確實屬於目的地伺服器，防範伺服器 ID 偽造廣播風險 (WebSocket Spoofing Risk)
            UUID actualServerId = message.getChannel().getServer().getId();
            if (!actualServerId.equals(serverId)) {
                log.warn(
                        "拒絕跨伺服器 WebSocket 訊息發送嘗試: 請求路徑伺服器={}, 實際通道伺服器={}",
                        serverId,
                        actualServerId);
                return;
            }

            // 2. 轉換為統一的 DTO 回應
            MessageResponse response = MessageResponse.fromEntity(message);

            // 3. 廣播給訂閱了該班級訊息流的所有客戶端 (使用從資料庫查詢出最安全真實的伺服器 ID)
            messagingTemplate.convertAndSend(
                    "/topic/servers/" + actualServerId + "/messages", response);

            meterRegistry.counter("chat.message.sent", "outcome", "success").increment();
            sample.stop(meterRegistry.timer("chat.message.broadcast"));

        } catch (Exception e) {
            // todo 發送失敗回傳訊息
            log.error("處理實時聊天訊息發生錯誤：", e);
            meterRegistry.counter("chat.message.sent", "outcome", "error").increment();
        }
    }
}
