package com.hys.classcord.ai.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.hys.classcord.ai.client.MaterialClient;
import com.hys.classcord.ai.service.AiAssistantService;
import com.hys.classcord.common.dto.InternalMaterialDto;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@ExtendWith(MockitoExtension.class)
class AiAssistantConsumerTest {

    private static final String BUCKET_NAME = "mock-bucket";

    @Mock private MaterialClient materialClient;
    @Mock private AiAssistantService aiAssistantService;
    @Mock private S3Client s3Client;

    private AiAssistantConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer =
                new AiAssistantConsumer(materialClient, aiAssistantService, s3Client, BUCKET_NAME);
    }

    private ResponseInputStream<GetObjectResponse> streamOf(String content) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(), new ByteArrayInputStream(content.getBytes()));
    }

    // 教材查不到（可能已被刪除）時，不應該嘗試下載或索引，安靜地直接返回
    @Test
    void handleRagProcessingMessage_doesNothing_whenMaterialNotFound() {
        UUID materialId = UUID.randomUUID();
        when(materialClient.getMaterial(materialId)).thenReturn(null);

        consumer.handleRagProcessingMessage(materialId.toString());

        verify(aiAssistantService, never()).indexMaterialAndEnable(any(), any());
        verify(aiAssistantService, never()).markMaterialAsFailed(any(), any());
        verifyNoInteractions(s3Client);
    }

    // 檔案網址符合 B2 bucket 前綴時，應該正確截取出物件 key 並在下載成功後交給 indexMaterialAndEnable
    @Test
    void handleRagProcessingMessage_extractsKeyFromBucketPrefix_andIndexesSuccessfully() {
        UUID materialId = UUID.randomUUID();
        String fileUrl =
                "https://s3.us-west-004.backblazeb2.com/"
                        + BUCKET_NAME
                        + "/materials/server1/syllabus.pdf";
        when(materialClient.getMaterial(materialId))
                .thenReturn(InternalMaterialDto.builder().id(materialId).fileUrl(fileUrl).build());
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(streamOf("file-bytes"));

        consumer.handleRagProcessingMessage(materialId.toString());

        ArgumentCaptor<GetObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().key()).isEqualTo("materials/server1/syllabus.pdf");
        verify(aiAssistantService).indexMaterialAndEnable(eq(materialId), any());
        verify(aiAssistantService, never()).markMaterialAsFailed(any(), any());
    }

    // 檔案網址不含 bucket 前綴時（例如自訂 CDN 網域），應該退回抓最後一段路徑當作 key
    @Test
    void handleRagProcessingMessage_fallsBackToLastSegment_whenUrlHasNoBucketPrefix() {
        UUID materialId = UUID.randomUUID();
        String fileUrl = "https://cdn.example.com/materials/server1/syllabus.pdf";
        when(materialClient.getMaterial(materialId))
                .thenReturn(InternalMaterialDto.builder().id(materialId).fileUrl(fileUrl).build());
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(streamOf("file-bytes"));

        consumer.handleRagProcessingMessage(materialId.toString());

        ArgumentCaptor<GetObjectRequest> requestCaptor =
                ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().key()).isEqualTo("syllabus.pdf");
    }

    // 下載第一次失敗（例如 B2 搬移中，檔案暫時不存在）、第二次成功時，應該自動重試後成功索引，不算失敗
    @Test
    void handleRagProcessingMessage_retriesAndSucceeds_afterTransientDownloadFailure() {
        UUID materialId = UUID.randomUUID();
        String fileUrl =
                "https://s3.us-west-004.backblazeb2.com/" + BUCKET_NAME + "/materials/retry.pdf";
        when(materialClient.getMaterial(materialId))
                .thenReturn(InternalMaterialDto.builder().id(materialId).fileUrl(fileUrl).build());
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("尚未就緒").build())
                .thenReturn(streamOf("file-bytes"));

        consumer.handleRagProcessingMessage(materialId.toString());

        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
        verify(aiAssistantService).indexMaterialAndEnable(eq(materialId), any());
        verify(aiAssistantService, never()).markMaterialAsFailed(any(), any());
    }

    // 重試次數用盡仍然失敗時，應該改為呼叫 markMaterialAsFailed 標記處理失敗，而不是讓例外一路往上炸掉整個 MQ 消費流程
    @Test
    void handleRagProcessingMessage_marksAsFailed_whenAllRetriesExhausted() {
        UUID materialId = UUID.randomUUID();
        String fileUrl =
                "https://s3.us-west-004.backblazeb2.com/" + BUCKET_NAME + "/materials/broken.pdf";
        when(materialClient.getMaterial(materialId))
                .thenReturn(InternalMaterialDto.builder().id(materialId).fileUrl(fileUrl).build());
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("永遠找不到").build());

        consumer.handleRagProcessingMessage(materialId.toString());

        verify(s3Client, times(5)).getObject(any(GetObjectRequest.class));
        verify(aiAssistantService, never()).indexMaterialAndEnable(any(), any());
        verify(aiAssistantService).markMaterialAsFailed(eq(materialId), any());
    }
}
