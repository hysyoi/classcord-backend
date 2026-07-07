package com.hys.classcord.server.service;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.server.dto.CreateServerRequest;
import com.hys.classcord.server.dto.UpdateServerRequest;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerErrorCode;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.exception.ServerException;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// todo 新增更改頻道擁有者方法、更改權限方法：
// 頻道擁有者（老師）<- 不得修改自己的角色，但可以轉讓擁有者
// 頻道擁有者可以修改別人的角色
// 老師可以修改學生為ta、或者ta為學生
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ServerService {

    private final ServerRepository serverRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final UserRepository userRepository;
    private final ChannelRepository channelRepository;

    // todo 硬編碼
    // 每人最大創建班級數
    private static final int MAX_OWNED_SERVERS = 10;
    // 每人最大加入班級數
    private static final int MAX_JOINED_SERVERS = 20;

    // todo 班級最大人數

    /** 建立伺服器（班級），同時將建立者設定為 TEACHER 角色 */
    @Transactional
    public Server createServer(UUID userId, CreateServerRequest request) {
        // 1. 悲觀鎖定當前使用者，防範並發建立時突破 n 個的限制
        User owner =
                userRepository
                        .findByIdForUpdate(userId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.USER_NOT_FOUND));
        // 2. 檢查建立上限
        long ownedCount = serverRepository.countByOwnerId(userId);
        if (ownedCount >= MAX_OWNED_SERVERS) {
            throw new ServerException(ServerErrorCode.MAX_SERVERS_CREATED);
        }
        // 3. 儲存伺服器
        Server server = Server.builder().name(request.name()).owner(owner).build();
        server = serverRepository.save(server);
        // 4. 將擁有者加入為成員，角色為 TEACHER
        ServerMember member =
                ServerMember.builder().server(server).user(owner).role(ServerRole.TEACHER).build();
        serverMemberRepository.save(member);

        // 5. 建立預設頻道：討論頻道 (GENERAL) + 管理頻道 (ADMIN)
        Channel discussionChannel =
                Channel.builder()
                        .server(server)
                        .name("討論頻道")
                        .type(ChannelType.GENERAL)
                        .position(0)
                        .build();
        channelRepository.save(discussionChannel);

        Channel adminChannel =
                Channel.builder()
                        .server(server)
                        .name("管理頻道")
                        .type(ChannelType.ADMIN)
                        .position(1)
                        .build();
        channelRepository.save(adminChannel);

        return server;
    }

    /** 加入伺服器，回傳加入後的成員實體 */
    @Transactional
    public ServerMember joinServer(UUID userId, UUID serverId) {
        Server server =
                serverRepository
                        .findById(serverId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.SERVER_NOT_FOUND));
        // 1. 悲觀鎖定當前使用者，防範並發加入時突破 n 個的限制
        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.USER_NOT_FOUND));
        // 2. 檢查加入上限
        long joinedCount = serverMemberRepository.countByUserId(userId);
        if (joinedCount >= MAX_JOINED_SERVERS) {
            throw new ServerException(ServerErrorCode.MAX_SERVERS_JOINED);
        }
        // 檢查是否已經是成員
        if (serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            throw new ServerException(ServerErrorCode.ALREADY_SERVER_MEMBER);
        }
        // 預設以 STUDENT 角色加入
        ServerMember member =
                ServerMember.builder().server(server).user(user).role(ServerRole.STUDENT).build();

        return serverMemberRepository.save(member);
    }

    /** 修改伺服器名稱（僅限 TEACHER 角色） */
    @Transactional
    public Server updateServer(UUID userId, UUID serverId, UpdateServerRequest request) {
        // 1. 確保伺服器存在
        Server server =
                serverRepository
                        .findById(serverId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.SERVER_NOT_FOUND));

        // 2. 獲取使用者在該伺服器的成員身份
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.NOT_SERVER_MEMBER));

        // 3. 權限驗證：確認角色是否為 TEACHER (比單純比對 ownerId 更具彈性)
        if (member.getRole() != ServerRole.TEACHER) {
            throw new ServerException(
                    ServerErrorCode.INSUFFICIENT_PERMISSIONS, "權限不足，只有教師角色才能修改伺服器名稱");
        }

        server.setName(request.name());

        return server;
    }

    /** 退出伺服器 */
    @Transactional
    public void leaveServer(UUID userId, UUID serverId) {
        Server server =
                serverRepository
                        .findById(serverId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.SERVER_NOT_FOUND));

        // 擁有者不能退出（擁有者必定是老師）
        if (server.getOwner().getId().equals(userId)) {
            throw new ServerException(ServerErrorCode.CANNOT_LEAVE_OWNER);
        }

        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.NOT_SERVER_MEMBER));

        serverMemberRepository.delete(member);
    }

    /** 獲取使用者加入的所有伺服器（Entity 列表） */
    public List<Server> getJoinedServers(UUID userId) {
        List<ServerMember> memberships =
                serverMemberRepository.findWithServerAndOwnerByUserId(userId);
        return memberships.stream().map(ServerMember::getServer).toList();
    }

    /** 獲取伺服器的成員（Entity 列表），必須是伺服器成員才能查看 */
    public List<ServerMember> getServerMembers(UUID userId, UUID serverId) {
        // 檢查查詢者是否為該伺服器成員
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            throw new ServerException(ServerErrorCode.NOT_SERVER_MEMBER);
        }

        return serverMemberRepository.findWithUserByServerId(serverId);
    }
}
