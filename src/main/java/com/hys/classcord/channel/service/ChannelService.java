package com.hys.classcord.channel.service;

import com.hys.classcord.channel.dto.ChannelPositionDto;
import com.hys.classcord.channel.dto.CreateChannelRequest;
import com.hys.classcord.channel.dto.UpdateChannelRequest;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelErrorCode;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.exception.ChannelException;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerErrorCode;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.exception.ServerException;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ServerRepository serverRepository;
    private final ServerMemberRepository serverMemberRepository;

    // todo 硬編碼
    private static final int MAX_CHANNELS_PER_SERVER = 50; // 若這裡修改，記得也要改error code訊息

    /** 建立頻道（僅限 Teacher/TA 權限，有限制數量上限） */
    @Transactional
    public Channel createChannel(UUID userId, UUID serverId, CreateChannelRequest request) {
        checkManagementPermission(serverId, userId);

        // 1. 使用悲觀鎖定伺服器記錄，防範並發建立頻道時的 Race Condition
        Server server =
                serverRepository
                        .findByIdForUpdate(serverId)
                        .orElseThrow(() -> new ServerException(ServerErrorCode.SERVER_NOT_FOUND));

        // 2. 檢查頻道數量限制 (此時安全無虞，因為同時間別的線程如果操作這個伺服器，會在這裡排隊等待)
        long channelCount = channelRepository.countByServerId(serverId);
        if (channelCount >= MAX_CHANNELS_PER_SERVER) {
            throw new ChannelException(ChannelErrorCode.MAX_CHANNELS_REACHED);
        }

        // 3. 自動計算下一個 position
        Integer maxPos = channelRepository.findMaxPositionByServerId(serverId);
        int position = (maxPos != null) ? maxPos + 1 : 0;

        Channel channel =
                Channel.builder()
                        .server(server)
                        .name(request.name())
                        .type(request.type())
                        .position(position)
                        .build();

        return channelRepository.save(channel);
    }

    /** 修改頻道名稱（僅限 Teacher/TA 權限） */
    @Transactional
    public Channel updateChannel(UUID userId, UUID channelId, UpdateChannelRequest request) {
        Channel channel =
                channelRepository
                        .findById(channelId)
                        .orElseThrow(
                                () -> new ChannelException(ChannelErrorCode.CHANNEL_NOT_FOUND));

        checkManagementPermission(channel.getServer().getId(), userId);

        // 只更新名稱
        channel.setName(request.name());

        return channel;
    }

    /** 批量更新頻道排序（僅限 Teacher/TA 權限） */
    @Transactional
    public void updateChannelPositions(
            UUID userId, UUID serverId, List<ChannelPositionDto> positionDtos) {

        if (positionDtos.isEmpty()) {
            return;
        }

        checkManagementPermission(serverId, userId);

        // 鎖定伺服器，保證該伺服器的排序操作同時只有一個人能做
        serverRepository
                .findByIdForUpdate(serverId)
                .orElseThrow(() -> new ServerException(ServerErrorCode.SERVER_NOT_FOUND));

        // 1. 一次撈出所有需要修改的頻道 (僅 1 次 SQL)
        List<UUID> channelIds = positionDtos.stream().map(ChannelPositionDto::id).toList();
        List<Channel> channels = channelRepository.findAllById(channelIds);

        // 2. 轉成 Map 便於快速查詢
        Map<UUID, Channel> channelMap =
                channels.stream().collect(Collectors.toMap(Channel::getId, Function.identity()));

        // 3. 更新 position 並進行所屬伺服器驗證 (避免跨伺服器攻擊)
        for (ChannelPositionDto dto : positionDtos) {
            Channel channel = channelMap.get(dto.id());
            if (channel == null || !channel.getServer().getId().equals(serverId)) {
                throw new ChannelException(ChannelErrorCode.CHANNEL_NOT_FOUND, "頻道不存在或不屬於此伺服器");
            }
            // 僅在排序確實改變時才設定，避免觸發無謂的 UPDATE SQL
            if (!Objects.equals(channel.getPosition(), dto.position())) {
                channel.setPosition(dto.position());
            }
        }
    }

    @Transactional
    public void deleteChannel(UUID userId, UUID channelId) {
        Channel channel =
                channelRepository
                        .findById(channelId)
                        .orElseThrow(
                                () -> new ChannelException(ChannelErrorCode.CHANNEL_NOT_FOUND));

        checkManagementPermission(channel.getServer().getId(), userId);

        channelRepository.delete(channel);
    }

    public List<Channel> getChannels(UUID userId, UUID serverId) {
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(
                                () -> new ChannelException(ChannelErrorCode.NOT_SERVER_MEMBER));

        List<Channel> channels = channelRepository.findByServerIdOrderByPositionAsc(serverId);

        // 若是學生，排除ADMIN頻道
        if (member.getRole() == ServerRole.STUDENT) {
            return channels.stream()
                    .filter(channel -> channel.getType() != ChannelType.ADMIN)
                    .toList();
        }

        return channels;
    }

    private void checkManagementPermission(UUID serverId, UUID userId) {
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(
                                () -> new ChannelException(ChannelErrorCode.NOT_SERVER_MEMBER));

        // 只有老師或者助教才有資格管理頻道
        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new ChannelException(ChannelErrorCode.INSUFFICIENT_PERMISSIONS);
        }
    }
}
