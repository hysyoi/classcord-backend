package com.hys.classcord.message.controller;

import com.hys.classcord.message.dto.CreateMessageRequest;
import com.hys.classcord.message.dto.MessageResponse;
import com.hys.classcord.message.dto.UpdateMessageRequest;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "訊息模組", description = "班級頻道內訊息的發送、讀取、編輯與刪除 API")
public class MessageController {

    private final MessageService messageService;

    @PostMapping("/channels/{channelId}/messages")
    @Operation(summary = "發送訊息", description = "在指定的頻道發送一條訊息（需為伺服器成員。學生不能在 MATERIAL 與 ADMIN 頻道發言）")
    public ResponseEntity<MessageResponse> sendMessage(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @Valid @RequestBody CreateMessageRequest request) {
        Message message = messageService.sendMessage(userId, channelId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.fromEntity(message));
    }

    @GetMapping("/channels/{channelId}/messages")
    @Operation(summary = "獲取頻道訊息列表", description = "分頁獲取指定頻道的歷史訊息（按時間倒序。學生不可存取 ADMIN 頻道）")
    public ResponseEntity<Page<MessageResponse>> getMessages(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Message> messages = messageService.getMessages(userId, channelId, page, size);
        Page<MessageResponse> responses = messages.map(MessageResponse::fromEntity);
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/messages/{messageId}")
    public ResponseEntity<Void> updateMessage(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID messageId,
            @Valid @RequestBody UpdateMessageRequest request) {

        messageService.updateMessage(userId, messageId, request);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/messages/{messageId}")
    @Operation(summary = "刪除訊息", description = "刪除指定訊息（限原作者，或是該伺服器的 Teacher/TA 角色）")
    public ResponseEntity<Void> deleteMessage(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID messageId) {
        messageService.deleteMessage(userId, messageId);
        return ResponseEntity.noContent().build();
    }
}
