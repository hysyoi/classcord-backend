package com.hys.classcord.ai.controller;

import com.hys.classcord.ai.dto.AiChatRequest;
import com.hys.classcord.ai.dto.AiChatResponse;
import com.hys.classcord.ai.dto.AiLimitStatusResponse;
import com.hys.classcord.ai.dto.AiMessageResponse;
import com.hys.classcord.ai.dto.AiSessionResponse;
import com.hys.classcord.ai.dto.CreateSessionRequest;
import com.hys.classcord.ai.service.AiAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/v1/materials")
@RequiredArgsConstructor
@Tag(name = "AI 助教模組", description = "提供教材 RAG 向量化啟用與問答對話的 API")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @PostMapping("/{materialId}/enable-ai")
    @Operation(
            summary = "啟用 AI 助教",
            description = "將教材狀態裝載為 PROCESSING，並推送任務至 RabbitMQ 背景進行 RAG 向量化")
    public ResponseEntity<Map<String, String>> enableAiAssistant(@PathVariable UUID materialId) {
        aiAssistantService.enableAiAssistant(materialId);
        return ResponseEntity.ok(Map.of("message", "AI 助教啟用請求已成功送出處理中"));
    }

    @PostMapping("/chat-sessions")
    @Operation(summary = "建立新對話會話", description = "建立針對特定教材的 AI 助教對話視窗")
    public ResponseEntity<AiSessionResponse> createSession(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CreateSessionRequest request) {
        var session = aiAssistantService.createSession(userId, request.materialId());
        return ResponseEntity.ok(AiSessionResponse.fromEntity(session));
    }

    @GetMapping("/chat-sessions")
    @Operation(summary = "查詢會話列表", description = "獲取使用者針對該教材建立的所有歷史會話")
    public ResponseEntity<List<AiSessionResponse>> listSessions(
            @AuthenticationPrincipal UUID userId, @RequestParam UUID materialId) {
        var sessions = aiAssistantService.listSessions(userId, materialId);
        var dtos = sessions.stream().map(AiSessionResponse::fromEntity).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/chat-sessions/{sessionId}/messages")
    @Operation(summary = "獲取會話歷史訊息", description = "查詢某對話視窗的最近 10 條訊息記錄")
    public ResponseEntity<List<AiMessageResponse>> getSessionMessages(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID sessionId) {
        var messages = aiAssistantService.getSessionMessages(userId, sessionId);
        var dtos = messages.stream().map(AiMessageResponse::fromEntity).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/chat-sessions/{sessionId}/chat")
    @Operation(summary = "會話連續對話", description = "在指定會話中發送新訊息，AI 將根據最近 10 條歷史記錄作為上下文進行回答")
    public ResponseEntity<AiChatResponse> chatInSession(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AiChatRequest request) {
        String answer = aiAssistantService.chatInSession(userId, sessionId, request.message());
        return ResponseEntity.ok(new AiChatResponse(answer));
    }

    @PostMapping(
            value = "/chat-sessions/{sessionId}/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "會話流式對話",
            description = "在指定會話中發送新訊息，AI 將以 Server-Sent Events (SSE) 格式逐字返回回答")
    public Flux<String> chatInSessionStream(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AiChatRequest request) {

        return aiAssistantService.chatInSessionStream(userId, sessionId, request.message());
    }

    @GetMapping("/ai-limit-status")
    @Operation(summary = "查詢全站 AI 呼叫額度與估算成本", description = "回傳今日累積呼叫次數、設定上限、使用進度百分比以及估算消費金額")
    public ResponseEntity<AiLimitStatusResponse> getAiLimitStatus() {
        return ResponseEntity.ok(aiAssistantService.getAiLimitStatus());
    }
}
