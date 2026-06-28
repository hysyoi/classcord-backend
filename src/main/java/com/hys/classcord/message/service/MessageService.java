package com.hys.classcord.message.service;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.core.config.ObjectStorageProperties;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.event.MaterialDeleteEvent;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.material.service.ObjectStorageService;
import com.hys.classcord.message.dto.CreateMessageRequest;
import com.hys.classcord.message.dto.UpdateMessageRequest;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.enums.MessageErrorCode;
import com.hys.classcord.message.exception.MessageException;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

// todo 可新增Redis訊息快取、即時通訊
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ServerRepository serverRepository;

    private final MaterialRepository materialRepository;
    private final ObjectStorageService storageService;
    private final ObjectStorageProperties storageProperties;
    private final S3Client s3Client;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    /** 發送訊息 */
    @Transactional
    public Message sendMessage(UUID userId, UUID channelId, CreateMessageRequest request) {
        // 1. 確認頻道存在
        Channel channel =
                channelRepository
                        .findById(channelId)
                        .orElseThrow(
                                () -> new MessageException(MessageErrorCode.CHANNEL_NOT_FOUND));

        // 2. 驗證是否為該伺服器的成員
        ServerMember member = getAndValidateMembership(channel.getServer().getId(), userId);

        // 3. 頻道權限驗證：
        //   - ADMIN 頻道：僅教師與助教能看/寫
        //   - MATERIAL 頻道：僅教師與助教能寫（學生唯讀）
        if (channel.getType() == ChannelType.ADMIN && member.getRole() == ServerRole.STUDENT) {
            throw new MessageException(
                    MessageErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師或助教才能在管理員頻道發言");
        }
        if (channel.getType() == ChannelType.MATERIAL && member.getRole() == ServerRole.STUDENT) {
            throw new MessageException(MessageErrorCode.MATERIAL_CHANNEL_POST_DENIED);
        }

        // 4. 獲取使用者實體
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new MessageException(
                                                MessageErrorCode.INSUFFICIENT_PERMISSIONS,
                                                "使用者不存在"));

        // 5. 建立並儲存訊息
        Message message =
                Message.builder().channel(channel).user(user).content(request.content()).build();

        return messageRepository.save(message);
    }

    // todo 在資料庫建立索引
    /** 獲取頻道的歷史訊息 (回傳 Page<Message> Entity 分頁) */
    public Page<Message> getMessages(UUID userId, UUID channelId, int page, int size) {
        // 1. 確認頻道存在
        Channel channel =
                channelRepository
                        .findById(channelId)
                        .orElseThrow(
                                () -> new MessageException(MessageErrorCode.CHANNEL_NOT_FOUND));

        // 2. 驗證是否為該伺服器的成員
        ServerMember member = getAndValidateMembership(channel.getServer().getId(), userId);

        // 3. 隱私過濾：學生不得存取 ADMIN 頻道
        if (channel.getType() == ChannelType.ADMIN && member.getRole() == ServerRole.STUDENT) {
            throw new MessageException(MessageErrorCode.ADMIN_CHANNEL_ACCESS_DENIED);
        }

        // 4. 限制 size 最大值以防止惡意大查詢
        int finalSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, finalSize, Sort.by("createdAt").descending());

        return messageRepository.findByChannelId(channelId, pageable);
    }

    /** 修改訊息內容 */
    @Transactional
    public void updateMessage(UUID userId, UUID messageId, UpdateMessageRequest request) {
        // 1. 確認訊息存在
        Message message =
                messageRepository
                        .findById(messageId)
                        .orElseThrow(
                                () -> new MessageException(MessageErrorCode.MESSAGE_NOT_FOUND));

        // 2. 權限檢查
        if (!userId.equals(message.getUser().getId())) {
            throw new MessageException(MessageErrorCode.INSUFFICIENT_PERMISSIONS, "您僅能修改自己發送的訊息");
        }

        // 3. 修改內容
        message.setContent(request.content());
    }

    /** 刪除訊息 (若有教材、一併刪除) */
    @Transactional
    public void deleteMessage(UUID userId, UUID messageId) {
        // 1. 確認訊息存在
        Message message =
                messageRepository
                        .findById(messageId)
                        .orElseThrow(
                                () -> new MessageException(MessageErrorCode.MESSAGE_NOT_FOUND));

        // 2. 權限檢查：如果是作者本人，可直接刪除
        if (message.getUser().getId().equals(userId)) {
            cleanUpAndExecuteDelete(message);
            return;
        }

        // 3. 如果非作者本身，必須是該伺服器的 Teacher 或 TA 角色 (管理員刪除)
        ServerMember member =
                getAndValidateMembership(message.getChannel().getServer().getId(), userId);
        if (member.getRole() == ServerRole.TEACHER || member.getRole() == ServerRole.TA) {
            cleanUpAndExecuteDelete(message);
        } else {
            throw new MessageException(MessageErrorCode.INSUFFICIENT_PERMISSIONS, "權限不足，無法刪除他人訊息");
        }
    }

    /** 刪除訊息前的清理輔助方法 */
    private void cleanUpAndExecuteDelete(Message message) {
        // 只有當頻道是教材頻道時，才去查詢並刪除 R2/B2 上的檔案與資料庫記錄
        if (message.getChannel().getType() == ChannelType.MATERIAL) {
            materialRepository
                    .findByMessageId(message.getId())
                    .ifPresent(
                            material -> {
                                String fileKey =
                                        storageService.extractFileKey(material.getFileUrl());

                                // 1. 扣減全站計數器 (Redis)
                                redisTemplate
                                        .opsForValue()
                                        .decrement("QUOTA:SYSTEM:USED", material.getFileSize());

                                // 2. 扣減班級容量 (使用悲觀鎖防止高併發 Lost Update)
                                Server server =
                                        serverRepository
                                                .findByIdForUpdate(
                                                        message.getChannel().getServer().getId())
                                                .orElseThrow(
                                                        () ->
                                                                new MessageException(
                                                                        MessageErrorCode
                                                                                .CHANNEL_NOT_FOUND,
                                                                        "找不到該伺服器"));
                                server.setUsedStorage(
                                        Math.max(
                                                0L,
                                                server.getUsedStorage() - material.getFileSize()));

                                // 3. 手動物理刪除教材記錄，釋放容量限額
                                materialRepository.delete(material);

                                // 4. 發送非同步刪除訊息給 RabbitMQ
                                rabbitTemplate.convertAndSend(
                                        RabbitMQConfig.MATERIAL_EXCHANGE,
                                        RabbitMQConfig.ROUTING_KEY_DELETE,
                                        new MaterialDeleteEvent(fileKey));
                            });
        }

        // 執行貼文刪除 (軟刪除)
        messageRepository.delete(message);
    }

    /** 驗證成員關係之私有輔助方法 */
    private ServerMember getAndValidateMembership(UUID serverId, UUID userId) {
        return serverMemberRepository
                .findByServerIdAndUserId(serverId, userId)
                .orElseThrow(() -> new MessageException(MessageErrorCode.NOT_SERVER_MEMBER));
    }
}
