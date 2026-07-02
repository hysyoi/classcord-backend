package com.hys.classcord.material.service;

import com.hys.classcord.core.config.ObjectStorageProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final ObjectStorageProperties properties;

    /** 產生安全且帶有 Content-Length 限制的預簽名上傳 URL (限時 10 分鐘) */
    public String generatePresignedUploadUrl(String fileKey, String contentType, long fileSize) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(properties.getBucketName())
                        .key(fileKey)
                        .contentType(contentType)
                        .build();

        PutObjectPresignRequest presignRequest =
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10))
                        .putObjectRequest(putObjectRequest)
                        .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /**
     * 產生安全且帶有時效、並強制瀏覽器下載的預簽名下載 URL
     *
     * @param fileKey 檔案在儲存桶中的唯一鍵
     * @param durationMinutes 下載網址的有效期限 (分鐘)
     * @param originalName 檔案的原始中文檔名 (用來指示瀏覽器下載時顯示的檔名)
     * @return 帶有簽章且強制下載的臨時網址
     */
    public String generatePresignedDownloadUrl(
            String fileKey, long durationMinutes, String originalName) {
        // 使用 RFC 8187 標準對中文檔名進行 URL 編碼，防止下載時中文檔名變成亂碼
        String encodedFilename =
                URLEncoder.encode(originalName, StandardCharsets.UTF_8).replace("+", "%20");
        String contentDisposition =
                String.format("attachment; filename*=UTF-8''%s", encodedFilename);

        GetObjectRequest getObjectRequest =
                GetObjectRequest.builder()
                        .bucket(properties.getBucketName())
                        .key(fileKey)
                        .responseContentDisposition(contentDisposition)
                        .build();

        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(durationMinutes))
                        .getObjectRequest(getObjectRequest)
                        .build();

        PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    /** 獲取檔案在 R2 中的永久公開 URL (在 Private 模式下，直接存取此 URL 會被拒絕) */
    public String getPublicFileUrl(String fileKey) {
        if (properties.getPublicUrl() != null && !properties.getPublicUrl().isBlank()) {
            String baseUrl = properties.getPublicUrl();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            return baseUrl + fileKey;
        }
        return properties.getEndpoint() + "/" + properties.getBucketName() + "/" + fileKey;
    }

    /**
     * 穩健地從資料庫儲存的「永久 URL」中提取檔案唯一鍵 (File Key)
     *
     * @param fileUrl 資料庫記錄的完整永久網址
     * @return 檔案唯一鍵 (用於 S3 SDK 簽章)
     */
    public String extractFileKey(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return "";
        }

        // 1. 比對自訂公開 CDN 網域前綴 (如 https://pub-xxx.r2.dev/)
        String publicUrl = properties.getPublicUrl();
        if (publicUrl != null && !publicUrl.isBlank()) {
            String prefix = publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
            if (fileUrl.startsWith(prefix)) {
                return fileUrl.substring(prefix.length());
            }
        }

        // 2. 比對 R2/S3 原生 Endpoint 網域前綴 (如 https://account.r2.cloudflarestorage.com/)
        String endpoint = properties.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            String prefix = endpoint.endsWith("/") ? endpoint : endpoint + "/";
            if (fileUrl.startsWith(prefix)) {
                String relativePath = fileUrl.substring(prefix.length());
                // 如果路徑開頭帶有 bucketName/，將其去除
                String bucketPrefix = properties.getBucketName() + "/";
                if (relativePath.startsWith(bucketPrefix)) {
                    return relativePath.substring(bucketPrefix.length());
                }
                return relativePath;
            }
        }

        // 3. 通用解析退路：解析 URI Path，並去除 Bucket 名稱
        try {
            java.net.URI uri = java.net.URI.create(fileUrl);
            String path = uri.getPath(); // 會拿到 "/materials/key.pdf" 或 "/bucket/materials/key.pdf"
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            String bucketPrefix = properties.getBucketName() + "/";
            if (path.startsWith(bucketPrefix)) {
                path = path.substring(bucketPrefix.length());
            }
            return path;
        } catch (Exception e) {
            // 最壞情況下的備用方案：只拿最後一個斜線後的內容
            int lastSlashIndex = fileUrl.lastIndexOf('/');
            if (lastSlashIndex != -1) {
                return fileUrl.substring(lastSlashIndex + 1);
            }
            return fileUrl;
        }
    }

    /**
     * 向 B2/S3 查詢已上傳檔案的真實大小（只讀取 Metadata，不下載檔案本體）
     *
     * @param fileKey 檔案在儲存桶中的唯一鍵
     * @return 真實檔案大小（bytes）；若檔案不存在（使用者未完成上傳）則回傳 -1
     */
    public long getActualObjectSize(String fileKey) {
        try {
            return s3Client.headObject(
                            HeadObjectRequest.builder()
                                    .bucket(properties.getBucketName())
                                    .key(fileKey)
                                    .build())
                    .contentLength();
        } catch (NoSuchKeyException e) {
            return -1L;
        }
    }

    /** 刪除儲存桶中的指定檔案（用於清除惡意或過期的臨時上傳物件） */
    public void deleteObject(String fileKey) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(properties.getBucketName())
                        .key(fileKey)
                        .build());
    }

    /** 在 B2/S3 儲存桶內部搬移檔案 */
    public void moveFile(String sourceKey, String destinationKey) {
        // 1. 複製物件
        CopyObjectRequest copyRequest =
                CopyObjectRequest.builder()
                        .sourceBucket(properties.getBucketName())
                        .sourceKey(sourceKey)
                        .destinationBucket(properties.getBucketName())
                        .destinationKey(destinationKey)
                        .build();
        s3Client.copyObject(copyRequest);

        // 2. 刪除原臨時物件（複用 deleteObject 方法）
        deleteObject(sourceKey);
    }
}
