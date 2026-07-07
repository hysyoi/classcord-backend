package com.hys.classcord.material.service;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.core.config.ObjectStorageProperties;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.dto.CreateMaterialRequest;
import com.hys.classcord.material.dto.UploadUrlResponse;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.event.MaterialDeleteEvent;
import com.hys.classcord.material.event.MaterialMoveEvent;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;

// todo 自訂網站前綴
// todo 高併發問題
// todo 可考慮@TransactionalEventListener
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ObjectStorageProperties properties;
    private final ObjectStorageService storageService;
    private final S3Client s3Client;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final RedisScript<Long> safeIncrScript;
    private final RedisScript<Long> safeDecrScript;
    private final ServerRepository serverRepository;
    private final RedissonClient redissonClient;

    private final RabbitTemplate rabbitTemplate;

    private static final int MAX_UPLOAD_ATTEMPTS = 10;
    private static final int UPLOAD_LIMIT_EXPIRE_SECONDS = 3600; // 1小時

    /** 1. 申請預簽名上傳網址 (包含全站與班級空間安全審查) */
    public UploadUrlResponse getUploadUrl(
            UUID userId, UUID channelId, String originalName, String contentType, long fileSize) {
        // 驗證頻道與成員權限
        Channel channel = getAndValidateChannel(channelId);
        ServerMember member = getAndValidateMembership(channel.getServer().getId(), userId);
        validateTeacherOrTaRole(member);

        // 1. Redis 帳號限流 (每小時最多上傳 10 次，採用原子 Lua 腳本)
        String rateLimitKey = "RATE_LIMIT:UPLOAD:" + userId;
        Long count =
                redisTemplate.execute(
                        rateLimitScript,
                        Collections.singletonList(rateLimitKey),
                        String.valueOf(UPLOAD_LIMIT_EXPIRE_SECONDS));
        if (count != null && count > MAX_UPLOAD_ATTEMPTS) {
            throw new MaterialException(MaterialErrorCode.UPLOAD_FREQUENCY_LIMIT_EXCEEDED);
        }

        // 2. 初始化全站已用容量計數器 (冷啟動防擊穿)
        initSystemQuotaCounterIfAbsent();

        // 3. 讀取全站已用容量並進行配額審查 (Redis 讀取)
        String systemUsedStr = redisTemplate.opsForValue().get("QUOTA:SYSTEM:USED");

        // 若回傳 null，代表 Redis 狀態異常，直接拋錯阻斷上傳
        if (systemUsedStr == null) {
            throw new MaterialException(
                    MaterialErrorCode.SYSTEM_STORAGE_LIMIT_EXCEEDED, "全站容量服務暫時不可用，請稍後再試");
        }

        long usedSystem = Long.parseLong(systemUsedStr);
        if (usedSystem + fileSize > properties.getSystemQuota()) {
            throw new MaterialException(MaterialErrorCode.SYSTEM_STORAGE_LIMIT_EXCEEDED);
        }

        // 4. 審查班級容量 (直接從 Server 實體讀取 usedStorage 欄位)
        Server server = channel.getServer();
        if (server.getUsedStorage() + fileSize > properties.getServerQuota()) {
            throw new MaterialException(MaterialErrorCode.SERVER_STORAGE_LIMIT_EXCEEDED);
        }

        // 5. 生成 B2 的臨時鍵 (隔離到 temp/ 臨時目錄中)
        String extension = "";
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot != -1) {
            extension = originalName.substring(lastDot);
        }
        String fileKey = String.format("temp/%s%s", UUID.randomUUID(), extension);

        // 6. 產生預簽名 PUT 網址
        String uploadUrl =
                storageService.generatePresignedUploadUrl(fileKey, contentType, fileSize);

        // 7. 在 Redis 寫入上傳憑證 (時效 15 分鐘)
        // 憑證格式：userId:fileSize:serverId
        String ticketValue = String.format("%s:%d:%s", userId, fileSize, server.getId());
        String ticketKey = "PENDING_UPLOAD:" + fileKey;
        redisTemplate.opsForValue().set(ticketKey, ticketValue, 15, TimeUnit.MINUTES);

        return new UploadUrlResponse(uploadUrl, fileKey);
    }

    /** 初始化全站已用容量計數器 (使用 Redisson 分散式鎖 + 雙重檢查，防擊穿) */
    private void initSystemQuotaCounterIfAbsent() {
        if (Boolean.FALSE.equals(redisTemplate.hasKey("QUOTA:SYSTEM:USED"))) {
            RLock lock = redissonClient.getLock("LOCK:SYSTEM:QUOTA");
            try {
                // 嘗試獲取鎖：最多等待 3 秒，搶到鎖後由 Watchdog 自動續期
                if (lock.tryLock(3, TimeUnit.SECONDS)) {
                    try {
                        // 第二層檢查 (Double Check)
                        if (Boolean.FALSE.equals(redisTemplate.hasKey("QUOTA:SYSTEM:USED"))) {
                            long usedSystem = materialRepository.sumAllFileSizes();
                            redisTemplate
                                    .opsForValue()
                                    .set("QUOTA:SYSTEM:USED", String.valueOf(usedSystem));
                            log.info("全站已用容量計數器初始化成功: {} Bytes", usedSystem);
                        }
                    } finally {
                        lock.unlock(); // 釋放鎖並自動廣播通知其他人
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // todo 需要仔細考察邊界情況
    /** 2. 發布教材貼文 (前端直傳成功後呼叫) */
    @Transactional
    public Material postMaterial(UUID userId, UUID channelId, CreateMaterialRequest request) {
        Channel channel = getAndValidateChannel(channelId);
        ServerMember member = getAndValidateMembership(channel.getServer().getId(), userId);
        validateTeacherOrTaRole(member);

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new MaterialException(MaterialErrorCode.USER_NOT_FOUND));

        // 1. 讀取並校驗 Redis 中的上傳憑證 (防偽造、防空 Key)
        String ticketKey = "PENDING_UPLOAD:" + request.fileKey();
        String ticketValue = redisTemplate.opsForValue().get(ticketKey);
        if (ticketValue == null) {
            throw new MaterialException(MaterialErrorCode.CHANNEL_NOT_FOUND, "上傳憑證已過期或不存在");
        }

        // 解析憑證 (格式: userId:fileSize:serverId)
        String[] parts = ticketValue.split(":");
        String ticketUserId = parts[0];
        long fileSize = Long.parseLong(parts[1]);
        String ticketServerId = parts[2];

        // 驗證憑證擁有者是否是當前操作者，防止 A 拿 B 申請的憑證去確認發文
        if (!ticketUserId.equals(userId.toString())) {
            throw new MaterialException(MaterialErrorCode.INSUFFICIENT_PERMISSIONS, "您非該上傳憑證的申請者");
        }

        // === 安全驗證：確認使用者真的已完成上傳（防止未上傳就呼叫確認） ===
        // 注意：超大檔案的防禦由 B2 在儲存桶層透過 Content-Length 簽章原生攔截，此處無需重複驗證大小
        long actualSize = storageService.getActualObjectSize(request.fileKey());
        if (actualSize == -1) {
            throw new MaterialException(MaterialErrorCode.UPLOAD_NOT_COMPLETED);
        }

        // 2. 確定目標路徑 (B2 搬移由 RabbitMQ 非同步處理)
        String newFileKey = request.fileKey().replace("temp/", "materials/" + ticketServerId + "/");

        // 3. 更新班級已用容量 (使用悲觀鎖防止高併發 Lost Update)
        Server server =
                serverRepository
                        .findByIdForUpdate(UUID.fromString(ticketServerId))
                        .orElseThrow(
                                () ->
                                        new MaterialException(
                                                MaterialErrorCode.INSUFFICIENT_PERMISSIONS,
                                                "找不到該伺服器"));

        // 雙重校驗容量限制，防止在申請 URL 到確認發布期間被其他併發上傳塞滿
        if (server.getUsedStorage() + fileSize > properties.getServerQuota()) {
            throw new MaterialException(
                    MaterialErrorCode.SERVER_STORAGE_LIMIT_EXCEEDED, "班級教材儲存空間已滿");
        }

        // 建立並儲存貼文
        Message message =
                Message.builder().channel(channel).user(user).content(request.content()).build();
        Message savedMessage = messageRepository.save(message);

        // 建立並儲存教材 (使用憑證鎖定的申請大小，實際上傳大小由 B2 Content-Length 簽章強制匹配)
        Material material =
                Material.builder()
                        .message(savedMessage)
                        .fileUrl(storageService.getPublicFileUrl(newFileKey))
                        .fileType(request.fileType())
                        .originalName(request.originalName())
                        .fileSize(fileSize)
                        .build();
        Material savedMaterial = materialRepository.save(material);

        // 更新班級已用容量
        server.setUsedStorage(server.getUsedStorage() + fileSize);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 只有資料庫 Commit 成功後，這裡才會被觸發！

                        // 安全更新全站計數器 (Redis 累加，僅在 Key 存在時執行)
                        redisTemplate.execute(
                                safeIncrScript,
                                Collections.singletonList("QUOTA:SYSTEM:USED"),
                                String.valueOf(fileSize));

                        // 手動清除 Redis 憑證
                        redisTemplate.delete(ticketKey);

                        // 4. 發送非同步搬移訊息給 RabbitMQ
                        rabbitTemplate.convertAndSend(
                                RabbitMQConfig.MATERIAL_EXCHANGE,
                                RabbitMQConfig.ROUTING_KEY_MOVE,
                                new MaterialMoveEvent(request.fileKey(), newFileKey));
                    }
                });

        return savedMaterial;
    }

    /** 3. 獲取帶有臨時下載網址的教材詳情 */
    public Material getMaterialWithDownloadUrl(UUID userId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        // 驗證是否為該班級成員，防止外人取得下載連結
        UUID serverId = materialRepository.findServerIdById(materialId);
        getAndValidateMembership(serverId, userId);

        // 嘗試從 Redis 快取讀取臨時網址 (快取期限與臨時下載網址效期保持一致，為 50 分鐘)
        String redisKey = "MATERIAL:DOWNLOAD_URL:" + materialId;
        String temporaryDownloadUrl = redisTemplate.opsForValue().get(redisKey);

        if (temporaryDownloadUrl == null) {
            // 提取 fileKey 並計算臨時下載連結 (設定 60 分鐘效期)
            String fileKey = storageService.extractFileKey(material.getFileUrl());
            temporaryDownloadUrl =
                    storageService.generatePresignedDownloadUrl(
                            fileKey, 60, material.getOriginalName());

            // 寫入 Redis 快取，有效期 50 分鐘 (與前端快取一致，安全期 10 分鐘)
            redisTemplate
                    .opsForValue()
                    .set(redisKey, temporaryDownloadUrl, 50, java.util.concurrent.TimeUnit.MINUTES);
            log.info("生成新的臨時下載連結並寫入 Redis 快取，教材 ID: {}", materialId);
        } else {
            log.info("從 Redis 快取命中教材臨時下載連結，教材 ID: {}", materialId);
        }

        // 為了不弄髒資料庫中的實體，返回一個設定了臨時網址的複本
        Material copy =
                Material.builder()
                        .message(material.getMessage())
                        .fileUrl(temporaryDownloadUrl)
                        .fileType(material.getFileType())
                        .originalName(material.getOriginalName())
                        .fileSize(material.getFileSize())
                        .build();

        copy.setId(material.getId()); // 用 Setter 把繼承自父類別的 ID 塞進去
        return copy;
    }

    /** 4. 刪除教材 (會連帶刪除 B2/R2 上的實體檔案) */
    @Transactional
    public void deleteMaterial(UUID userId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        Message message = material.getMessage();
        UUID serverId = message.getChannel().getServer().getId();
        ServerMember member = getAndValidateMembership(serverId, userId);

        // 權限檢查：必須是上傳者本人，或者是該班級的 TEACHER/TA
        boolean isAuthor = message.getUser().getId().equals(userId);
        boolean isTeacherOrTa =
                member.getRole() == ServerRole.TEACHER || member.getRole() == ServerRole.TA;

        if (!isAuthor && !isTeacherOrTa) {
            throw new MaterialException(MaterialErrorCode.INSUFFICIENT_PERMISSIONS);
        }

        // 扣減班級容量 (使用悲觀鎖防止高併發 Lost Update)
        Server server =
                serverRepository
                        .findByIdForUpdate(serverId)
                        .orElseThrow(
                                () ->
                                        new MaterialException(
                                                MaterialErrorCode.INSUFFICIENT_PERMISSIONS,
                                                "找不到該伺服器"));
        server.setUsedStorage(Math.max(0L, server.getUsedStorage() - material.getFileSize()));

        // 手動物理刪除教材記錄
        materialRepository.delete(material);

        // 軟刪除訊息貼文
        messageRepository.delete(message);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 只有資料庫 Commit 成功後，這裡才會被觸發！

                        // 安全扣減全站計數器 (Redis 累減，僅在 Key 存在時執行)
                        redisTemplate.execute(
                                safeDecrScript,
                                Collections.singletonList("QUOTA:SYSTEM:USED"),
                                String.valueOf(material.getFileSize()));

                        // 發送非同步刪除訊息給 RabbitMQ (解耦 B2 網路刪除)
                        String fileKey = storageService.extractFileKey(material.getFileUrl());
                        rabbitTemplate.convertAndSend(
                                RabbitMQConfig.MATERIAL_EXCHANGE,
                                RabbitMQConfig.ROUTING_KEY_DELETE,
                                new MaterialDeleteEvent(fileKey));
                    }
                });
    }

    /* 私有輔助方法 */

    private Channel getAndValidateChannel(UUID channelId) {
        Channel channel =
                channelRepository
                        .findById(channelId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getType() != ChannelType.MATERIAL) {
            throw new MaterialException(MaterialErrorCode.INVALID_CHANNEL_TYPE);
        }
        return channel;
    }

    private ServerMember getAndValidateMembership(UUID serverId, UUID userId) {
        return serverMemberRepository
                .findByServerIdAndUserId(serverId, userId)
                .orElseThrow(() -> new MaterialException(MaterialErrorCode.NOT_SERVER_MEMBER));
    }

    private void validateTeacherOrTaRole(ServerMember member) {
        if (member.getRole() == ServerRole.STUDENT) {
            throw new MaterialException(MaterialErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師或助教能發布教材");
        }
    }
}
