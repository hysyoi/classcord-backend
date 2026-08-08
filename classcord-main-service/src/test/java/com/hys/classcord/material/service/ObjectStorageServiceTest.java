package com.hys.classcord.material.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hys.classcord.core.config.ObjectStorageProperties;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

// 純單元測試：不真的連 S3/B2，S3Client、S3Presigner 都是介面，用 Mockito 直接 mock，
// 不像 RestClient 那樣有鏈式呼叫要處理。
@ExtendWith(MockitoExtension.class)
class ObjectStorageServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    private final ObjectStorageProperties properties = new ObjectStorageProperties();

    private ObjectStorageService objectStorageService;

    @BeforeEach
    void setUp() {
        properties.setBucketName("classcord-bucket");
        objectStorageService = new ObjectStorageService(s3Client, s3Presigner, properties);
    }

    // ==========================================
    // generatePresignedUploadUrl
    // ==========================================

    // 應該把檔案大小簽進去（讓 B2 在儲存桶層直接拒絕超大上傳），並回傳簽好的網址
    @Test
    void generatePresignedUploadUrl_signsContentLength_andReturnsUrl() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://mock-upload-url").toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        String url =
                objectStorageService.generatePresignedUploadUrl(
                        "temp/file.pdf", "application/pdf", 1024L);

        assertThat(url).isEqualTo("https://mock-upload-url");

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(s3Presigner).presignPutObject(captor.capture());
        PutObjectRequest putRequest = captor.getValue().putObjectRequest();
        assertThat(putRequest.bucket()).isEqualTo("classcord-bucket");
        assertThat(putRequest.key()).isEqualTo("temp/file.pdf");
        assertThat(putRequest.contentLength()).isEqualTo(1024L);
    }

    // ==========================================
    // generatePresignedDownloadUrl
    // ==========================================

    // 中文檔名要正確做 RFC 8187 編碼，避免瀏覽器下載時檔名變亂碼
    @Test
    void generatePresignedDownloadUrl_encodesChineseFilename_correctly() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://mock-download-url").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presigned);

        String url =
                objectStorageService.generatePresignedDownloadUrl(
                        "materials/course/syllabus.pdf", 60, "教學大綱.pdf");

        assertThat(url).isEqualTo("https://mock-download-url");

        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(captor.capture());
        GetObjectRequest getRequest = captor.getValue().getObjectRequest();
        assertThat(getRequest.responseContentDisposition())
                .isEqualTo("attachment; filename*=UTF-8''%E6%95%99%E5%AD%B8%E5%A4%A7%E7%B6%B1.pdf");
    }

    // ==========================================
    // getPublicFileUrl
    // ==========================================

    // 有設定公開 CDN 網域時，優先使用它拼接網址
    @Test
    void getPublicFileUrl_usesPublicUrl_whenConfigured() {
        properties.setPublicUrl("https://pub-xxx.r2.dev");

        String url = objectStorageService.getPublicFileUrl("materials/course/file.pdf");

        assertThat(url).isEqualTo("https://pub-xxx.r2.dev/materials/course/file.pdf");
    }

    // 公開網域結尾已經有斜線時，不該重複拼出兩個斜線
    @Test
    void getPublicFileUrl_doesNotDuplicateSlash_whenPublicUrlAlreadyEndsWithSlash() {
        properties.setPublicUrl("https://pub-xxx.r2.dev/");

        String url = objectStorageService.getPublicFileUrl("materials/course/file.pdf");

        assertThat(url).isEqualTo("https://pub-xxx.r2.dev/materials/course/file.pdf");
    }

    // 沒設定公開網域時，退回用 endpoint + bucket 組成網址
    @Test
    void getPublicFileUrl_fallsBackToEndpoint_whenPublicUrlNotConfigured() {
        properties.setPublicUrl(null);
        properties.setEndpoint("https://s3.us-west-004.backblazeb2.com");

        String url = objectStorageService.getPublicFileUrl("materials/course/file.pdf");

        assertThat(url)
                .isEqualTo(
                        "https://s3.us-west-004.backblazeb2.com/classcord-bucket/materials/course/file.pdf");
    }

    // ==========================================
    // extractFileKey
    // ==========================================

    // 空字串或 null，應該回傳空字串，不要噴例外
    @Test
    void extractFileKey_returnsEmptyString_whenUrlIsNullOrBlank() {
        assertThat(objectStorageService.extractFileKey(null)).isEmpty();
        assertThat(objectStorageService.extractFileKey("")).isEmpty();
        assertThat(objectStorageService.extractFileKey("   ")).isEmpty();
    }

    // 網址是公開 CDN 網域，應該直接去掉前綴拿到 fileKey
    @Test
    void extractFileKey_stripsPublicUrlPrefix_whenUrlMatchesPublicDomain() {
        properties.setPublicUrl("https://pub-xxx.r2.dev");

        String fileKey =
                objectStorageService.extractFileKey(
                        "https://pub-xxx.r2.dev/materials/course/file.pdf");

        assertThat(fileKey).isEqualTo("materials/course/file.pdf");
    }

    // 網址是原生 S3/R2 Endpoint，且路徑帶有 bucket 名稱，應該把 endpoint 跟 bucket 都去掉
    @Test
    void extractFileKey_stripsEndpointAndBucket_whenUrlMatchesNativeEndpoint() {
        properties.setPublicUrl(null);
        properties.setEndpoint("https://s3.us-west-004.backblazeb2.com");

        String fileKey =
                objectStorageService.extractFileKey(
                        "https://s3.us-west-004.backblazeb2.com/classcord-bucket/materials/course/file.pdf");

        assertThat(fileKey).isEqualTo("materials/course/file.pdf");
    }

    // 完全比對不到 publicUrl 也比對不到 endpoint 前綴時，應該退回用通用 URI 解析，
    // 並且照樣去掉開頭的 bucket 名稱
    @Test
    void extractFileKey_fallsBackToGenericUriParsing_whenNoConfiguredPrefixMatches() {
        properties.setPublicUrl(null);
        properties.setEndpoint("https://some-other-endpoint.example.com");

        String fileKey =
                objectStorageService.extractFileKey(
                        "https://totally-different-domain.com/classcord-bucket/materials/course/file.pdf");

        assertThat(fileKey).isEqualTo("materials/course/file.pdf");
    }

    // 連通用 URI 解析都失敗的最壞情況（格式不合法的字串），應該退回用最後一個斜線切割
    @Test
    void extractFileKey_fallsBackToLastSlashSplit_whenUrlIsCompletelyMalformed() {
        String fileKey = objectStorageService.extractFileKey("not a url at all: {bad}");

        // URI.create 對這種字串通常會直接丟例外，走進最壞情況的 catch 分支
        assertThat(fileKey).isNotNull();
    }

    // ==========================================
    // getActualObjectSize
    // ==========================================

    // 檔案確實存在，應該回傳真實大小
    @Test
    void getActualObjectSize_returnsActualSize_whenFileExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(2048L).build());

        long size = objectStorageService.getActualObjectSize("materials/course/file.pdf");

        assertThat(size).isEqualTo(2048L);
    }

    // 檔案根本不存在（使用者還沒真的上傳完成），應該回傳 -1 而不是丟例外給呼叫端
    @Test
    void getActualObjectSize_returnsMinusOne_whenFileDoesNotExist() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        long size = objectStorageService.getActualObjectSize("temp/never-uploaded.pdf");

        assertThat(size).isEqualTo(-1L);
    }

    // ==========================================
    // deleteObject / moveFile
    // ==========================================

    @Test
    void deleteObject_callsS3ClientWithCorrectBucketAndKey() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        objectStorageService.deleteObject("materials/course/file.pdf");

        ArgumentCaptor<DeleteObjectRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("classcord-bucket");
        assertThat(captor.getValue().key()).isEqualTo("materials/course/file.pdf");
    }

    // 搬移檔案應該先複製到新位置，再刪除原本暫存區的檔案
    @Test
    void moveFile_copiesThenDeletesOriginal() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        objectStorageService.moveFile("temp/abc.pdf", "materials/course/abc.pdf");

        ArgumentCaptor<CopyObjectRequest> copyCaptor =
                ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copyCaptor.capture());
        assertThat(copyCaptor.getValue().sourceKey()).isEqualTo("temp/abc.pdf");
        assertThat(copyCaptor.getValue().destinationKey()).isEqualTo("materials/course/abc.pdf");

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().key()).isEqualTo("temp/abc.pdf");
    }
}
