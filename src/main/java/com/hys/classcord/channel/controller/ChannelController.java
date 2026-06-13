package com.hys.classcord.channel.controller;

import com.hys.classcord.channel.dto.ChannelResponse;
import com.hys.classcord.channel.dto.CreateChannelRequest;
import com.hys.classcord.channel.dto.UpdateChannelRequest;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.service.ChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "頻道模組", description = "班級伺服器內頻道的管理 API")
public class ChannelController {

    private final ChannelService channelService;

    @PostMapping("/servers/{serverId}/channels")
    @Operation(summary = "在伺服器建立頻道", description = "在指定的伺服器建立新頻道（限 Teacher/TA 權限）")
    public ResponseEntity<ChannelResponse> createChannel(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID serverId,
            @Valid @RequestBody CreateChannelRequest request) {
        Channel channel = channelService.createChannel(userId, serverId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ChannelResponse.fromEntity(channel));
    }

    @GetMapping("/servers/{serverId}/channels")
    @Operation(summary = "獲取伺服器的頻道列表", description = "取得伺服器內的所有頻道（學生角色會自動隱藏 ADMIN 類型頻道）")
    public ResponseEntity<List<ChannelResponse>> getChannels(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID serverId) {
        List<Channel> channels = channelService.getChannels(userId, serverId);
        List<ChannelResponse> responses =
                channels.stream().map(ChannelResponse::fromEntity).toList();
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/channels/{channelId}")
    @Operation(summary = "修改頻道內容", description = "修改指定頻道的名稱和排序（限 Teacher/TA 權限）")
    public ResponseEntity<ChannelResponse> updateChannel(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID channelId,
            @Valid @RequestBody UpdateChannelRequest request) {
        Channel channel = channelService.updateChannel(userId, channelId, request);
        return ResponseEntity.ok(ChannelResponse.fromEntity(channel));
    }

    @DeleteMapping("/channels/{channelId}")
    @Operation(summary = "刪除頻道", description = "刪除指定的頻道（限 Teacher/TA 權限）")
    public ResponseEntity<Void> deleteChannel(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID channelId) {
        channelService.deleteChannel(userId, channelId);
        return ResponseEntity.noContent().build();
    }
}
