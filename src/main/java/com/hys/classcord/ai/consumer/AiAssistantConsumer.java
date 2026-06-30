package com.hys.classcord.ai.consumer;

import com.hys.classcord.ai.strategy.RagIndexingStrategy;
import com.hys.classcord.ai.strategy.RagStrategyFactory;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.repository.MaterialRepository;
import java.io.InputStream;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Slf4j
@Component
public class AiAssistantConsumer {

    private final MaterialRepository materialRepository;
    private final RagStrategyFactory strategyFactory;
    private final S3Client s3Client;
    private final String bucketName;

    public AiAssistantConsumer(
            MaterialRepository materialRepository,
            RagStrategyFactory strategyFactory,
            S3Client s3Client,
            @Value("${app.storage.bucket-name}") String bucketName) {
        this.materialRepository = materialRepository;
        this.strategyFactory = strategyFactory;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Transactional
    @RabbitListener(queues = RabbitMQConfig.RAG_PROCESS_QUEUE)
    public void handleRagProcessingMessage(String materialIdStr) {
        UUID materialId = UUID.fromString(materialIdStr);
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material == null) return;

        try {
            log.info("【MQ Consumer】收到 RAG 處理消息：從 B2 下載教材... materialId={}", materialId);

            byte[] fileBytes = downloadFileFromB2(material.getFileUrl());
            RagIndexingStrategy strategy = strategyFactory.getStrategy();
            strategy.processAndIndex(material, fileBytes);

            material.markAsEnabled();
            materialRepository.save(material);
            log.info("【MQ Consumer】AI 助教啟用成功！materialId={}", materialId);

        } catch (Exception e) {
            log.error("【MQ Consumer】AI 助教啟用失敗 materialId=" + materialId, e);
            material.markAsFailed(e.getMessage());
            materialRepository.save(material);
        }
    }

    // todo 可考慮其他重試機制
    private byte[] downloadFileFromB2(String fileUrl) throws Exception {
        String objectKey = extractObjectKey(fileUrl);
        GetObjectRequest request =
                GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

        int maxRetries = 5;
        int delayMs = 1000;

        for (int i = 0; i < maxRetries; i++) {
            try (InputStream in = s3Client.getObject(request)) {
                return in.readAllBytes();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    throw e;
                }
                log.warn(
                        "教材正式檔案 {} 尚未就緒（可能正在進行 B2 搬移），將於 {} 毫秒後重試下載 (第 {}/{} 次)...",
                        objectKey,
                        delayMs,
                        i + 1,
                        maxRetries);
                Thread.sleep(delayMs);
            }
        }
        throw new IllegalStateException("無法下載教材檔案: " + objectKey);
    }

    private String extractObjectKey(String fileUrl) {
        String pattern = "/" + bucketName + "/";
        // 提取 /{bucketName}/ 之後的完整路徑（含資料夾）
        if (fileUrl.contains(pattern)) {
            return fileUrl.substring(fileUrl.indexOf(pattern) + pattern.length());
        }

        // 備用回退機制
        if (fileUrl.contains("/")) {
            return fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
        }
        return fileUrl;
    }
}
