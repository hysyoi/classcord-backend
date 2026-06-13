package com.hys.classcord.server.controller;

import com.hys.classcord.server.dto.CreateServerRequest;
import com.hys.classcord.server.dto.ServerMemberResponse;
import com.hys.classcord.server.dto.ServerResponse;
import com.hys.classcord.server.dto.UpdateServerRequest;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.service.ServerService;
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
@RequestMapping("/v1/servers")
@RequiredArgsConstructor
@Tag(name = "伺服器模組", description = "班級伺服器與成員管理的 API")
public class ServerController {

    private final ServerService serverService;

    @PostMapping
    @Operation(summary = "建立伺服器", description = "建立新伺服器，並自動將建立者設為 TEACHER 角色")
    public ResponseEntity<ServerResponse> createServer(
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateServerRequest request) {
        Server server = serverService.createServer(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServerResponse.fromEntity(server));
    }

    @PostMapping("/{serverId}/join")
    @Operation(summary = "加入伺服器", description = "將目前登入的用戶加入該伺服器，預設角色為 STUDENT")
    public ResponseEntity<ServerMemberResponse> joinServer(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID serverId) {
        ServerMember member = serverService.joinServer(userId, serverId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ServerMemberResponse.fromEntity(member));
    }

    @PutMapping("/{serverId}")
    @Operation(summary = "修改伺服器名稱", description = "修改指定伺服器的名稱（限教師角色權限）")
    public ResponseEntity<ServerResponse> updateServer(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID serverId,
            @Valid @RequestBody UpdateServerRequest request) {
        Server server = serverService.updateServer(userId, serverId, request);
        return ResponseEntity.ok(ServerResponse.fromEntity(server));
    }

    @PostMapping("/{serverId}/leave")
    @Operation(summary = "退出伺服器", description = "退出指定的伺服器（伺服器擁有者不可退出）")
    public ResponseEntity<Void> leaveServer(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID serverId) {
        serverService.leaveServer(userId, serverId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "獲取已加入的伺服器列表", description = "取得當前登入用戶所加入的所有伺服器")
    public ResponseEntity<List<ServerResponse>> getJoinedServers(
            @AuthenticationPrincipal UUID userId) {
        List<Server> servers = serverService.getJoinedServers(userId);
        List<ServerResponse> responses = servers.stream().map(ServerResponse::fromEntity).toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{serverId}/members")
    @Operation(summary = "獲取伺服器成員列表", description = "取得指定伺服器的所有成員列表（查詢者本身必須是該伺服器的成員）")
    public ResponseEntity<List<ServerMemberResponse>> getServerMembers(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID serverId) {
        List<ServerMember> members = serverService.getServerMembers(userId, serverId);
        List<ServerMemberResponse> responses =
                members.stream().map(ServerMemberResponse::fromEntity).toList();
        return ResponseEntity.ok(responses);
    }
}
