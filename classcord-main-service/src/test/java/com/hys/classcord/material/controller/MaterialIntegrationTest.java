package com.hys.classcord.material.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.dto.CreateMaterialRequest;
import com.hys.classcord.material.dto.UploadUrlResponse;
import com.hys.classcord.material.event.MaterialDeleteEvent;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class MaterialIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ServerMemberRepository serverMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RabbitTemplate rabbitTemplate;

    private User teacher;
    private User student;
    private User outsider;
    private Channel materialChannel;
    private Channel generalChannel;

    private String teacherToken;
    private String studentToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() throws Exception {

        // 1. 初始化使用者與 Token
        teacher =
                userRepository.save(
                        User.builder().username("Teacher").email("teacher@test.com").build());
        student =
                userRepository.save(
                        User.builder().username("Student").email("student@test.com").build());
        outsider =
                userRepository.save(
                        User.builder().username("Outsider").email("outsider@test.com").build());

        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());
        studentToken = "Bearer " + jwtUtils.generateToken(student.getId(), student.getEmail());
        outsiderToken = "Bearer " + jwtUtils.generateToken(outsider.getId(), outsider.getEmail());

        // 2. 建立伺服器與成員
        Server testServer =
                serverRepository.save(Server.builder().name("Test Class").owner(teacher).build());

        serverMemberRepository.save(
                ServerMember.builder()
                        .server(testServer)
                        .user(teacher)
                        .role(ServerRole.TEACHER)
                        .build());
        serverMemberRepository.save(
                ServerMember.builder()
                        .server(testServer)
                        .user(student)
                        .role(ServerRole.STUDENT)
                        .build());

        // 3. 建立頻道 (教材頻道與一般討論頻道)
        materialChannel =
                channelRepository.save(
                        Channel.builder()
                                .server(testServer)
                                .name("課程教材")
                                .type(ChannelType.MATERIAL)
                                .position(0)
                                .build());

        generalChannel =
                channelRepository.save(
                        Channel.builder()
                                .server(testServer)
                                .name("一般討論")
                                .type(ChannelType.GENERAL)
                                .position(1)
                                .build());

        // 4. Mock S3Presigner 預簽名上傳與下載的行為 (使用 doReturn 確保執行緒安全，防範背景 Consumer 執行緒併發調用導致 Mockito
        // 狀態混亂)
        PresignedPutObjectRequest mockPresignedPut = mock(PresignedPutObjectRequest.class);
        doReturn(
                        URI.create(
                                        "https://s3.us-west-004.backblazeb2.com/classcord/mock-upload-url")
                                .toURL())
                .when(mockPresignedPut)
                .url();
        doReturn(mockPresignedPut)
                .when(s3Presigner)
                .presignPutObject(any(PutObjectPresignRequest.class));

        PresignedGetObjectRequest mockPresignedGet = mock(PresignedGetObjectRequest.class);
        doReturn(
                        URI.create(
                                        "https://s3.us-west-004.backblazeb2.com/classcord/mock-download-url")
                                .toURL())
                .when(mockPresignedGet)
                .url();
        doReturn(mockPresignedGet)
                .when(s3Presigner)
                .presignGetObject(any(GetObjectPresignRequest.class));

        // 5. Mock S3Client 的 copyObject 與 deleteObject 行為，避免 AWS SDK 執行真實網路連線
        doReturn(CopyObjectResponse.builder().build())
                .when(s3Client)
                .copyObject(any(CopyObjectRequest.class));
        doReturn(DeleteObjectResponse.builder().build())
                .when(s3Client)
                .deleteObject(any(DeleteObjectRequest.class));

        // 6. Mock S3Client.headObject，模擬 B2 回報的真實檔案大小（與 postRequest 的 fileSize 一致）
        //    這讓 MaterialService 中的 HeadObject 二階段驗證能正常通過
        doReturn(HeadObjectResponse.builder().contentLength(102400L).build())
                .when(s3Client)
                .headObject(any(HeadObjectRequest.class));
    }

    // 以老師身分申請上傳網址，回傳 UploadUrlResponse，供需要「已取得上傳網址」的測試重用
    private UploadUrlResponse requestUploadUrl(String filename) throws Exception {
        String uploadUrlRes =
                mockMvc.perform(
                                get("/v1/channels/"
                                                + materialChannel.getId()
                                                + "/materials/upload-url")
                                        .header("Authorization", teacherToken)
                                        .param("filename", filename)
                                        .param("contentType", "application/pdf")
                                        .param("fileSize", "102400")) // 100KB
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readValue(uploadUrlRes, UploadUrlResponse.class);
    }

    private record PublishedMaterial(UUID messageId, UUID materialId) {}

    // 以老師身分完整跑完「申請上傳網址 ➔ 發布教材貼文」流程，供需要「已存在教材」的測試重用
    private PublishedMaterial publishMaterial() throws Exception {
        UploadUrlResponse uploadResponse = requestUploadUrl("syllabus.pdf");

        CreateMaterialRequest postRequest =
                new CreateMaterialRequest(
                        "各位同學好，這是本學期的教學大綱",
                        uploadResponse.fileKey(),
                        "pdf",
                        "syllabus.pdf",
                        102400L);

        String createdMessageJson =
                mockMvc.perform(
                                post("/v1/channels/" + materialChannel.getId() + "/materials")
                                        .header("Authorization", teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(postRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID messageId =
                UUID.fromString(objectMapper.readTree(createdMessageJson).get("id").asText());
        UUID materialId =
                UUID.fromString(
                        objectMapper
                                .readTree(createdMessageJson)
                                .get("materials")
                                .get(0)
                                .get("id")
                                .asText());
        return new PublishedMaterial(messageId, materialId);
    }

    // 只有老師能申請教材上傳網址，學生申請應被拒絕
    @Test
    void testRequestUploadUrl_TeacherOnly() throws Exception {
        // 教師申請上傳網址 ➔ 成功 (200 OK)
        UploadUrlResponse uploadResponse = requestUploadUrl("syllabus.pdf");
        assertNotNull(uploadResponse.uploadUrl());
        assertNotNull(uploadResponse.fileKey());
        assertTrue(uploadResponse.fileKey().startsWith("temp/"));

        // 學生申請上傳網址 ➔ 失敗 (403 Forbidden)
        mockMvc.perform(
                        get("/v1/channels/" + materialChannel.getId() + "/materials/upload-url")
                                .header("Authorization", studentToken)
                                .param("filename", "syllabus.pdf")
                                .param("contentType", "application/pdf")
                                .param("fileSize", "102400"))
                .andExpect(status().isForbidden());
    }

    // 只有老師能發布教材貼文，學生發布應被拒絕
    @Test
    void testPublishMaterial_TeacherOnly() throws Exception {
        UploadUrlResponse uploadResponse = requestUploadUrl("syllabus.pdf");
        CreateMaterialRequest postRequest =
                new CreateMaterialRequest(
                        "各位同學好，這是本學期的教學大綱",
                        uploadResponse.fileKey(),
                        "pdf",
                        "syllabus.pdf",
                        102400L);

        // 學生發布教材貼文 ➔ 失敗 (403 Forbidden)
        mockMvc.perform(
                        post("/v1/channels/" + materialChannel.getId() + "/materials")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(postRequest)))
                .andExpect(status().isForbidden());

        // 教師確認發布教材貼文 ➔ 成功 (201 Created)
        mockMvc.perform(
                        post("/v1/channels/" + materialChannel.getId() + "/materials")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(postRequest)))
                .andExpect(status().isCreated());
    }

    // 班級成員（學生）可查詢教材詳情並取得下載連結，非成員（局外人）應被拒絕
    @Test
    void testGetMaterialDetail_MemberOnly() throws Exception {
        PublishedMaterial material = publishMaterial();

        // 班級成員(學生)獲取教材詳情 ➔ 成功 (200 OK)，且拿到臨時下載 url
        String getMaterialRes =
                mockMvc.perform(
                                get("/v1/materials/" + material.materialId())
                                        .header("Authorization", studentToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String fileUrl = objectMapper.readTree(getMaterialRes).get("fileUrl").asText();
        assertEquals("https://s3.us-west-004.backblazeb2.com/classcord/mock-download-url", fileUrl);

        // 局外人(Outsider)獲取教材詳情 ➔ 失敗 (403 Forbidden)
        mockMvc.perform(
                        get("/v1/materials/" + material.materialId())
                                .header("Authorization", outsiderToken))
                .andExpect(status().isForbidden());
    }

    // 刪除教材訊息後，應連帶刪除 S3 上的實體檔案，且資料庫中的訊息與教材紀錄也要正確清除
    @Test
    void testDeleteMaterialMessage_CascadesToS3AndDatabase() throws Exception {
        PublishedMaterial material = publishMaterial();

        // 教師刪除該條教材訊息
        mockMvc.perform(
                        delete("/v1/messages/" + material.messageId())
                                .header("Authorization", teacherToken))
                .andExpect(status().isNoContent());

        // 觸發在測試交易中註冊的 afterCommit 回呼以發送 MQ 訊息
        triggerAfterCommitCallbacks();

        // 驗證 A: JPA 查不到該條 message 和 material
        assertFalse(messageRepository.existsById(material.messageId()));
        assertFalse(materialRepository.existsById(material.materialId()));

        // 驗證 B: S3 刪除方法有被調用 (非同步等待 RabbitMQ Consumer 執行)
        verify(s3Client, timeout(3000).atLeastOnce()).deleteObject(any(DeleteObjectRequest.class));

        // 驗證 C: 透過 Native SQL 檢查軟刪除在 DB 層面確實將 messages.deleted 設為 true
        Boolean messageDeleted =
                jdbcTemplate.queryForObject(
                        "SELECT deleted FROM messages WHERE id = ?",
                        Boolean.class,
                        material.messageId());
        assertTrue(messageDeleted, "資料庫中的 deleted 欄位應為 true");

        // 驗證 D: materials 資料表中的實體，已被刪除（因為 materials 本身未設軟刪除）
        Integer materialCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM materials WHERE id = ?",
                        Integer.class,
                        material.materialId());
        assertEquals(0, materialCount, "教材關聯紀錄應該已被物理刪除");
    }

    // 申請上傳的檔案超過班級容量上限時，應該被攔截拒絕
    @Test
    void testUploadQuotaValidation() throws Exception {
        // 測試全站/班級容量配額超出限制的攔截
        // 本地預設班級限額為 500MB，嘗試申請一個 600MB 的檔案上傳
        long hugeSize = 600 * 1024 * 1024L; // 600MB

        mockMvc.perform(
                        get("/v1/channels/" + materialChannel.getId() + "/materials/upload-url")
                                .header("Authorization", teacherToken)
                                .param("filename", "huge_movie.mp4")
                                .param("contentType", "video/mp4")
                                .param("fileSize", String.valueOf(hugeSize)))
                .andExpect(
                        status().isPayloadTooLarge()); // 應拋出自訂例外，最後被全域 Exception Handler 攔截返回 413
    }

    // S3 刪除持續失敗、重試用盡後，任務訊息應該被轉移到死信佇列，不會憑空消失
    @Test
    void testDeadLetterQueueOnFailure() throws Exception {
        // 0. 清空死信佇列中先前的殘留訊息
        while (rabbitTemplate.receiveAndConvert(RabbitMQConfig.DELETE_DLQ) != null) {}

        // 1. 模擬 S3 物理刪除時發生嚴重故障拋出例外
        doThrow(new RuntimeException("模擬 B2 雲端機房大當機！"))
                .when(s3Client)
                .deleteObject(any(DeleteObjectRequest.class));

        // 2. 發送刪除任務給 RabbitMQ
        String failedFileKey = "materials/test-server/failed-file.pdf";
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.MATERIAL_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_DELETE,
                new MaterialDeleteEvent(failedFileKey));

        // 3. 從死信佇列 (DLQ) 中非同步拉取訊息 (給予重試時間最長 8 秒)
        // 因為重試設定為 1s -> 2s -> 4s，三次重試失敗後訊息會被轉移至 DLQ
        MaterialDeleteEvent dlqEvent =
                (MaterialDeleteEvent)
                        rabbitTemplate.receiveAndConvert(RabbitMQConfig.DELETE_DLQ, 8000);

        // 4. 驗證死信佇列中確實保存了該筆失敗的任務訊息
        assertNotNull(dlqEvent, "死信佇列中應該要收到重試失敗的任務訊息");
        assertEquals(failedFileKey, dlqEvent.fileKey(), "死信佇列中的檔案 Key 應一致");
    }
}
