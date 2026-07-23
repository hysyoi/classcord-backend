package com.hys.classcord.material.controller;

import com.hys.classcord.material.dto.CreateMaterialRequest;
import com.hys.classcord.material.dto.MaterialResponse;
import com.hys.classcord.material.dto.UploadUrlResponse;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.service.MaterialService;
import com.hys.classcord.message.dto.MessageResponse;
import com.hys.classcord.message.entity.Message;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "教材模組", description = "教材檔案的預簽名上傳、確認發布、獲取下載網址的 API")
public class MaterialController {

    private final MaterialService materialService;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping("/channels/{channelId}/materials/upload-url")
    @Operation(
            summary = "申請教材預簽名上傳網址",
            description = "取得直傳儲存桶的預簽名 PUT URL，並進行全站與班級空間限額審查（限 Teacher/TA）")
    public ResponseEntity<UploadUrlResponse> getUploadUrl(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @RequestParam String filename,
            @RequestParam String contentType,
            @RequestParam long fileSize) {
        UploadUrlResponse response =
                materialService.getUploadUrl(userId, channelId, filename, contentType, fileSize);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/channels/{channelId}/materials")
    @Operation(summary = "確認發布教材貼文", description = "前端直傳檔案成功後，呼叫此端點確認完成教材貼文發布（限 Teacher/TA）")
    public ResponseEntity<MessageResponse> postMaterial(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @Valid @RequestBody CreateMaterialRequest request) {
        // 1. 呼叫服務層，拿到教材實體
        Material material = materialService.postMaterial(userId, channelId, request);
        // 2. 從教材中取得關聯的貼文
        Message message = material.getMessage();
        // 3. 組裝回應 DTO（已在Service組裝好關聯物件，不會有懶加載問題）
        MessageResponse response = MessageResponse.fromEntity(message, List.of(material));
        // 4. 廣播給訂閱了該班級訊息流的所有客戶端，確保全體在線成員能即時接收新教材
        try {
            messagingTemplate.convertAndSend(
                    "/topic/servers/" + message.getChannel().getServer().getId() + "/messages",
                    response);
        } catch (Exception e) {
            log.error("廣播教材貼文訊息失敗：", e);
        }
        // 5. 回傳 DTO
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/materials/{materialId}")
    @Operation(summary = "獲取教材詳情 (含臨時下載連結)", description = "取得單個教材的元數據，並動態產生成限時 60 分鐘的安全下載 URL")
    public ResponseEntity<MaterialResponse> getMaterial(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID materialId) {
        Material material = materialService.getMaterialWithDownloadUrl(userId, materialId);
        return ResponseEntity.ok(MaterialResponse.fromEntity(material));
    }
}
