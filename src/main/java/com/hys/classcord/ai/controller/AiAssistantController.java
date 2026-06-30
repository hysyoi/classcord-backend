package com.hys.classcord.ai.controller;

import com.hys.classcord.ai.dto.AiChatRequest;
import com.hys.classcord.ai.dto.AiChatResponse;
import com.hys.classcord.ai.service.AiAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// todo 權限
@RestController
@RequestMapping("/v1/materials")
@RequiredArgsConstructor
@Tag(name = "AI 助教模組", description = "提供教材 RAG 向量化啟用與問答對話的 API")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @PostMapping("/{materialId}/enable-ai")
    @Operation(
            summary = "啟用 AI 助教",
            description = "將教材狀態鎖定為 PROCESSING，並推送任務至 RabbitMQ 背景進行 RAG 向量化")
    public ResponseEntity<Map<String, String>> enableAiAssistant(@PathVariable UUID materialId) {
        aiAssistantService.enableAiAssistant(materialId);
        return ResponseEntity.ok(Map.of("message", "AI 助教啟用請求已成功送出處理中"));
    }

    @PostMapping("/{materialId}/ai-chat")
    @Operation(summary = "教材 AI 助教問答 (RAG)", description = "針對已啟用的教材進行問答，系統將過濾該教材切片並組合上下文回傳")
    public ResponseEntity<AiChatResponse> chatWithAi(
            @PathVariable UUID materialId, @Valid @RequestBody AiChatRequest request) {

        String answer = aiAssistantService.chatWithMaterial(materialId, request.message());
        return ResponseEntity.ok(new AiChatResponse(answer));
    }
}
