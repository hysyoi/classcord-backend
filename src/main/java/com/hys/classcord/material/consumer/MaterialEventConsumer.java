package com.hys.classcord.material.consumer;

import com.hys.classcord.core.config.ObjectStorageProperties;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.event.MaterialDeleteEvent;
import com.hys.classcord.material.event.MaterialMoveEvent;
import com.hys.classcord.material.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

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
        } catch (Exception e) {
            log.error("[MQ Consumer] 檔案搬移失敗: {}", event.sourceKey(), e);
            // 可根據業務需求拋出異常，觸發 RabbitMQ 重試機制或進入死信佇列
            throw e;
        }
    }

    /** 2. 監聽刪除佇列，背景執行 B2 檔案刪除 */
    @RabbitListener(queues = RabbitMQConfig.DELETE_QUEUE)
    public void handleMaterialDelete(MaterialDeleteEvent event) {
        log.info("[MQ Consumer] 收到檔案刪除任務: {}", event.fileKey());
        try {
            s3Client.deleteObject(
                    builder ->
                            builder.bucket(storageProperties.getBucketName()).key(event.fileKey()));
            log.info("[MQ Consumer] B2 檔案物理刪除成功: {}", event.fileKey());
        } catch (Exception e) {
            log.error("[MQ Consumer] B2 檔案刪除失敗: {}", event.fileKey(), e);
            throw e;
        }
    }
}
