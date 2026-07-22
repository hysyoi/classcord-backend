package com.hys.classcord.material.controller;

import com.hys.classcord.material.service.MaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/internal/materials")
@RequiredArgsConstructor
@Tag(name = "教材內部微服務 API", description = "供其他微服務 (如 AI 服務) 呼叫的內部介面")
public class InternalMaterialController {

    private final MaterialService materialService;

    @PutMapping("/{id}/status/processing")
    @Operation(summary = "標記教材為 AI 處理中", description = "使用悲觀鎖鎖定教材並更新狀態為 PROCESSING")
    public ResponseEntity<Void> markAsProcessing(@PathVariable UUID id) {
        materialService.markAsProcessing(id);
        log.info("【內部 API】已成功標記教材 {} 狀態為 PROCESSING", id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "獲取教材內部詳情", description = "供其他微服務查詢教材狀態與元數據")
    public ResponseEntity<com.hys.classcord.common.dto.InternalMaterialDto> getMaterial(
            @PathVariable UUID id) {
        return ResponseEntity.ok(materialService.getInternalMaterial(id));
    }

    @PutMapping("/{id}/status/enabled")
    @Operation(summary = "標記教材為已啟用 AI 助教", description = "更新狀態為 ENABLED 並發送 WebSocket 廣播通知前端")
    public ResponseEntity<Void> markAsEnabled(@PathVariable UUID id) {
        materialService.markAsEnabled(id);
        log.info("【內部 API】已成功標記教材 {} 狀態為 ENABLED", id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/status/failed")
    @Operation(summary = "標記教材為 AI 處理失敗", description = "更新狀態為 FAILED 並記錄錯誤訊息")
    public ResponseEntity<Void> markAsFailed(
            @PathVariable UUID id,
            @RequestBody(required = false)
                    com.hys.classcord.common.dto.FailMaterialRequest request) {
        String errorMessage = request != null ? request.errorMessage() : null;
        materialService.markAsFailed(id, errorMessage);
        log.info("【內部 API】已成功標記教材 {} 狀態為 FAILED: {}", id, errorMessage);
        return ResponseEntity.ok().build();
    }
}
