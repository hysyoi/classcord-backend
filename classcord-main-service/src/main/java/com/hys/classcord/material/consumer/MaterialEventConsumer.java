package com.hys.classcord.material.consumer;

import com.hys.classcord.core.config.ObjectStorageProperties;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.event.MaterialDeleteEvent;
import com.hys.classcord.material.event.MaterialMoveEvent;
import com.hys.classcord.material.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Component
@Slf4j
@RequiredArgsConstructor
public class MaterialEventConsumer {

    private final ObjectStorageService storageService;
    private final ObjectStorageProperties storageProperties;
    private final S3Client s3Client;

    /** 1. 監聽搬移佇列，背景執行 B2 檔案搬移 (temp/ -> materials/) */
    @RabbitListener(queues = RabbitMQConfig.MOVE_QUEUE)
    public void handleMaterialMove(MaterialMoveEvent event) {
        log.info("[MQ Consumer] 收到檔案搬移任務: {} -> {}", event.sourceKey(), event.targetKey());
        try {
            storageService.moveFile(event.sourceKey(), event.targetKey());
            log.info("[MQ Consumer] 檔案搬移完成: {}", event.targetKey());
        } catch (NoSuchKeyException e) {
            // 捕獲 S3 找不到檔案的不可恢復錯誤，直接拒絕不重試，發往 DLQ
            log.warn("[MQ Consumer] 源檔案不存在，跳過搬移並送入死信佇列: {}", event.sourceKey());
            throw new AmqpRejectAndDontRequeueException("源檔案不存在，拒絕重試", e);
        } catch (Exception e) {
            log.error("[MQ Consumer] 檔案搬移失敗: {}", event.sourceKey(), e);
            throw e;
        }
    }

    /** 2. 監聽刪除佇列，背景執行 B2 檔案刪除 */
    @RabbitListener(queues = RabbitMQConfig.DELETE_QUEUE)
    public void handleMaterialDelete(MaterialDeleteEvent event) {
        log.info("[MQ Consumer] 收到檔案刪除任務: {}", event.fileKey());
        try {
            storageService.deleteObject(event.fileKey());
            log.info("[MQ Consumer] B2 檔案物理刪除成功: {}", event.fileKey());
        } catch (Exception e) {
            log.error("[MQ Consumer] B2 檔案刪除失敗: {}", event.fileKey(), e);
            throw e;
        }
    }
}
