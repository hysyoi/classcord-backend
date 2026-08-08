package com.hys.classcord.material.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.core.config.ObjectStorageProperties;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.dto.CreateMaterialRequest;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import software.amazon.awssdk.services.s3.S3Client;

// 純單元測試：不啟動 Spring context、不連真的 Redis/DB，全部依賴用 Mockito 替身取代。
// postMaterial/deleteMaterial 原本用 TransactionSynchronizationManager.registerSynchronization()
// 無條件註冊 afterCommit 回呼，在沒有真正交易的單元測試環境下呼叫會直接丟例外；已經比照
// AuthenticationService 的寫法加上 isSynchronizationActive() 判斷（單元測試環境下天生是 false，
// 會直接同步執行，不需要額外模擬交易同步機制），因此這裡也能直接測到完整邏輯。
@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    @Mock private MaterialRepository materialRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServerMemberRepository serverMemberRepository;
    @Mock private ObjectStorageService storageService;
    @Mock private S3Client s3Client;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private RedisScript<Long> rateLimitScript;
    @Mock private RedisScript<Long> safeIncrScript;
    @Mock private RedisScript<Long> safeDecrScript;
    @Mock private ServerRepository serverRepository;
    @Mock private RedissonClient redissonClient;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RabbitTemplate rabbitTemplate;

    private final ObjectStorageProperties properties = new ObjectStorageProperties();

    private MaterialService materialService;

    private UUID teacherId;
    private UUID channelId;
    private Server server;
    private Channel materialChannel;

    @BeforeEach
    void setUp() {
        properties.setSystemQuota(1_000_000L);
        properties.setServerQuota(500_000L);

        materialService =
                new MaterialService(
                        materialRepository,
                        messageRepository,
                        channelRepository,
                        userRepository,
                        serverMemberRepository,
                        properties,
                        storageService,
                        s3Client,
                        redisTemplate,
                        rateLimitScript,
                        safeIncrScript,
                        safeDecrScript,
                        serverRepository,
                        redissonClient,
                        messagingTemplate,
                        rabbitTemplate);

        teacherId = UUID.randomUUID();
        channelId = UUID.randomUUID();
        server = Server.builder().name("Test Class").usedStorage(0L).build();
        server.setId(UUID.randomUUID());
        materialChannel = Channel.builder().server(server).type(ChannelType.MATERIAL).build();
        materialChannel.setId(channelId);
    }

    // 假裝全站容量計數器已經初始化過，避免每個測試都要另外 mock Redisson 分散式鎖的細節
    private void stubSystemQuotaCounterAlreadyInitialized(long usedSystemBytes) {
        when(redisTemplate.hasKey("QUOTA:SYSTEM:USED")).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("QUOTA:SYSTEM:USED")).thenReturn(String.valueOf(usedSystemBytes));
    }

    private void stubTeacherMembership() {
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(materialChannel));
        ServerMember member =
                ServerMember.builder().server(server).role(ServerRole.TEACHER).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(member));
    }

    private void stubRateLimitOk() {
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any())).thenReturn(1L);
    }

    // 目標頻道不是教材頻道類型，應該拒絕
    @Test
    void getUploadUrl_throwsInvalidChannelType_whenChannelIsNotMaterialChannel() {
        Channel generalChannel = Channel.builder().server(server).type(ChannelType.GENERAL).build();
        generalChannel.setId(channelId);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(generalChannel));

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId, channelId, "file.pdf", "application/pdf", 1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.INVALID_CHANNEL_TYPE.getCode());
    }

    // 不是該伺服器成員，應該拒絕
    @Test
    void getUploadUrl_throwsNotServerMember_whenUserIsNotAMember() {
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(materialChannel));
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId, channelId, "file.pdf", "application/pdf", 1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.NOT_SERVER_MEMBER.getCode());
    }

    // 學生沒有發布教材的權限，應該拒絕
    @Test
    void getUploadUrl_throwsInsufficientPermissions_whenUserIsStudent() {
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(materialChannel));
        ServerMember student =
                ServerMember.builder().server(server).role(ServerRole.STUDENT).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(student));

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId, channelId, "file.pdf", "application/pdf", 1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.INSUFFICIENT_PERMISSIONS.getCode());
    }

    // 一小時內上傳次數超過上限，應該拒絕
    @Test
    void getUploadUrl_throwsUploadFrequencyLimitExceeded_whenRateLimited() {
        stubTeacherMembership();
        when(redisTemplate.execute(eq(rateLimitScript), anyList(), any())).thenReturn(11L);

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId, channelId, "file.pdf", "application/pdf", 1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.UPLOAD_FREQUENCY_LIMIT_EXCEEDED.getCode());
    }

    // Redis 裡的全站容量計數器讀不到值（服務異常），應該保守拒絕上傳，而不是放行
    @Test
    void getUploadUrl_throwsSystemStorageLimitExceeded_whenQuotaCounterUnavailable() {
        stubTeacherMembership();
        stubRateLimitOk();
        when(redisTemplate.hasKey("QUOTA:SYSTEM:USED")).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("QUOTA:SYSTEM:USED")).thenReturn(null);

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId, channelId, "file.pdf", "application/pdf", 1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.SYSTEM_STORAGE_LIMIT_EXCEEDED.getCode());
    }

    // 全站已用容量加上這次檔案大小會超過全站配額上限，應該拒絕
    @Test
    void getUploadUrl_throwsSystemStorageLimitExceeded_whenOverSystemQuota() {
        stubTeacherMembership();
        stubRateLimitOk();
        stubSystemQuotaCounterAlreadyInitialized(999_500L); // 只剩 500 bytes 額度

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId,
                                        channelId,
                                        "big-file.pdf",
                                        "application/pdf",
                                        1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.SYSTEM_STORAGE_LIMIT_EXCEEDED.getCode());
    }

    // 全站容量還夠，但這個班級自己的容量會被塞滿，應該拒絕
    @Test
    void getUploadUrl_throwsServerStorageLimitExceeded_whenOverServerQuota() {
        server.setUsedStorage(499_500L); // 只剩 500 bytes 班級額度
        stubTeacherMembership();
        stubRateLimitOk();
        stubSystemQuotaCounterAlreadyInitialized(0L);

        assertThatThrownBy(
                        () ->
                                materialService.getUploadUrl(
                                        teacherId,
                                        channelId,
                                        "big-file.pdf",
                                        "application/pdf",
                                        1000))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.SERVER_STORAGE_LIMIT_EXCEEDED.getCode());
    }

    // 所有檢查都通過，應該產生暫存區的 fileKey（保留副檔名）並回傳預簽名上傳網址
    @Test
    void getUploadUrl_returnsPresignedUrl_withTempFileKey_whenAllChecksPass() {
        stubTeacherMembership();
        stubRateLimitOk();
        stubSystemQuotaCounterAlreadyInitialized(0L);
        when(storageService.generatePresignedUploadUrl(any(), eq("application/pdf"), eq(1000L)))
                .thenReturn("https://mock-upload-url");

        var result =
                materialService.getUploadUrl(
                        teacherId, channelId, "syllabus.pdf", "application/pdf", 1000);

        assertThat(result.uploadUrl()).isEqualTo("https://mock-upload-url");
        assertThat(result.fileKey()).startsWith("temp/");
        assertThat(result.fileKey()).endsWith(".pdf");
    }

    // ==========================================
    // postMaterial
    // ==========================================

    private void stubTeacherMembershipAndUser() {
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(materialChannel));
        ServerMember member =
                ServerMember.builder().server(server).role(ServerRole.TEACHER).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(member));
        User teacher = User.builder().username("Teacher").email("teacher@test.com").build();
        teacher.setId(teacherId);
        when(userRepository.findById(teacherId)).thenReturn(Optional.of(teacher));
    }

    private CreateMaterialRequest buildPostRequest(String fileKey, long fileSize) {
        return new CreateMaterialRequest("上課講義", fileKey, "pdf", "syllabus.pdf", fileSize);
    }

    // Redis 裡查不到上傳憑證（過期或偽造的 fileKey），應該拒絕
    @Test
    void postMaterial_throwsTicketNotFound_whenUploadTicketMissing() {
        stubTeacherMembershipAndUser();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("PENDING_UPLOAD:temp/abc.pdf")).thenReturn(null);

        CreateMaterialRequest request = buildPostRequest("temp/abc.pdf", 1000L);

        assertThatThrownBy(() -> materialService.postMaterial(teacherId, channelId, request))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.CHANNEL_NOT_FOUND.getCode());
    }

    // 憑證是別人申請的，不能被冒用來確認發布，防止 A 拿 B 申請的憑證發文
    @Test
    void postMaterial_throwsInsufficientPermissions_whenTicketBelongsToSomeoneElse() {
        stubTeacherMembershipAndUser();
        UUID someoneElseId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("PENDING_UPLOAD:temp/abc.pdf"))
                .thenReturn(someoneElseId + ":1000:" + server.getId());

        CreateMaterialRequest request = buildPostRequest("temp/abc.pdf", 1000L);

        assertThatThrownBy(() -> materialService.postMaterial(teacherId, channelId, request))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.INSUFFICIENT_PERMISSIONS.getCode());
    }

    // 憑證有效，但 B2 上其實還沒真的上傳完成（有人跳過上傳直接呼叫確認發布），應該拒絕
    @Test
    void postMaterial_throwsUploadNotCompleted_whenFileNotActuallyUploaded() {
        stubTeacherMembershipAndUser();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("PENDING_UPLOAD:temp/abc.pdf"))
                .thenReturn(teacherId + ":1000:" + server.getId());
        when(storageService.getActualObjectSize("temp/abc.pdf")).thenReturn(-1L);

        CreateMaterialRequest request = buildPostRequest("temp/abc.pdf", 1000L);

        assertThatThrownBy(() -> materialService.postMaterial(teacherId, channelId, request))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.UPLOAD_NOT_COMPLETED.getCode());
    }

    // 申請網址到確認發布這段期間，班級容量被其他併發上傳塞滿了，雙重校驗應該擋下
    @Test
    void postMaterial_throwsServerStorageLimitExceeded_whenQuotaFilledUpConcurrently() {
        stubTeacherMembershipAndUser();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("PENDING_UPLOAD:temp/abc.pdf"))
                .thenReturn(teacherId + ":1000:" + server.getId());
        when(storageService.getActualObjectSize("temp/abc.pdf")).thenReturn(1000L);
        server.setUsedStorage(499_500L); // 只剩 500 bytes 額度，這次要用 1000 bytes
        when(serverRepository.findByIdForUpdate(server.getId())).thenReturn(Optional.of(server));

        CreateMaterialRequest request = buildPostRequest("temp/abc.pdf", 1000L);

        assertThatThrownBy(() -> materialService.postMaterial(teacherId, channelId, request))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.SERVER_STORAGE_LIMIT_EXCEEDED.getCode());
    }

    // 所有檢查都通過：應該把檔案搬出暫存區、更新班級容量、清除 Redis 憑證，
    // 並派發非同步搬移訊息給 RabbitMQ
    @Test
    void postMaterial_succeeds_updatesQuotaAndDispatchesMoveEvent_whenAllChecksPass() {
        stubTeacherMembershipAndUser();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("PENDING_UPLOAD:temp/abc.pdf"))
                .thenReturn(teacherId + ":1000:" + server.getId());
        when(storageService.getActualObjectSize("temp/abc.pdf")).thenReturn(1000L);
        when(serverRepository.findByIdForUpdate(server.getId())).thenReturn(Optional.of(server));
        when(messageRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            Message m = invocation.getArgument(0);
                            m.setId(UUID.randomUUID());
                            return m;
                        });
        when(materialRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            Material m = invocation.getArgument(0);
                            m.setId(UUID.randomUUID());
                            return m;
                        });
        when(storageService.getPublicFileUrl(any())).thenReturn("https://mock-public-url");

        CreateMaterialRequest request = buildPostRequest("temp/abc.pdf", 1000L);
        Material result = materialService.postMaterial(teacherId, channelId, request);

        assertThat(result).isNotNull();
        assertThat(server.getUsedStorage()).isEqualTo(1000L);
        verify(redisTemplate).execute(eq(safeIncrScript), anyList(), eq("1000"));
        verify(redisTemplate).delete("PENDING_UPLOAD:temp/abc.pdf");
        verify(rabbitTemplate)
                .convertAndSend(
                        eq(RabbitMQConfig.MATERIAL_EXCHANGE),
                        eq(RabbitMQConfig.ROUTING_KEY_MOVE),
                        any(Object.class));
    }

    // ==========================================
    // deleteMaterial
    // ==========================================

    private Material buildMaterialWithMessage(User author, long fileSize) {
        Message message = Message.builder().channel(materialChannel).user(author).build();
        message.setId(UUID.randomUUID());
        Material material =
                Material.builder()
                        .message(message)
                        .fileUrl("https://bucket/materials/test-server/file.pdf")
                        .fileType("pdf")
                        .originalName("syllabus.pdf")
                        .fileSize(fileSize)
                        .build();
        material.setId(UUID.randomUUID());
        return material;
    }

    // 教材不存在，應該拒絕
    @Test
    void deleteMaterial_throwsMaterialNotFound_whenMaterialDoesNotExist() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findById(materialId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> materialService.deleteMaterial(teacherId, materialId))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.MATERIAL_NOT_FOUND.getCode());
    }

    // 既不是上傳者本人、也不是教師/助教，不能刪除別人的教材
    @Test
    void deleteMaterial_throwsInsufficientPermissions_whenNeitherAuthorNorTeacher() {
        User author = User.builder().username("Author").email("author@test.com").build();
        author.setId(UUID.randomUUID());
        Material material = buildMaterialWithMessage(author, 1000L);
        UUID materialId = material.getId();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));

        UUID outsiderStudentId = UUID.randomUUID();
        ServerMember student =
                ServerMember.builder().server(server).role(ServerRole.STUDENT).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), outsiderStudentId))
                .thenReturn(Optional.of(student));

        assertThatThrownBy(() -> materialService.deleteMaterial(outsiderStudentId, materialId))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.INSUFFICIENT_PERMISSIONS.getCode());
    }

    // 上傳者本人可以刪除自己的教材：應該扣減班級容量、物理刪除教材、軟刪除訊息，並派發刪除訊息給 RabbitMQ
    @Test
    void deleteMaterial_succeeds_whenCalledByAuthor() {
        User author = User.builder().username("Author").email("author@test.com").build();
        author.setId(teacherId);
        Material material = buildMaterialWithMessage(author, 1000L);
        UUID materialId = material.getId();
        server.setUsedStorage(5000L);
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        ServerMember student =
                ServerMember.builder().server(server).role(ServerRole.STUDENT).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(student));
        when(serverRepository.findByIdForUpdate(server.getId())).thenReturn(Optional.of(server));
        when(storageService.extractFileKey(material.getFileUrl()))
                .thenReturn("materials/test-server/file.pdf");

        materialService.deleteMaterial(teacherId, materialId);

        assertThat(server.getUsedStorage()).isEqualTo(4000L);
        verify(materialRepository).delete(material);
        verify(messageRepository).delete(material.getMessage());
        verify(redisTemplate).execute(eq(safeDecrScript), anyList(), eq("1000"));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq(RabbitMQConfig.MATERIAL_EXCHANGE),
                        eq(RabbitMQConfig.ROUTING_KEY_DELETE),
                        eventCaptor.capture());
    }

    // 老師/助教即使不是上傳者本人，也可以代為刪除教材（班級管理權限）
    @Test
    void deleteMaterial_succeeds_whenCalledByTeacherWhoIsNotAuthor() {
        User author = User.builder().username("Student").email("student@test.com").build();
        author.setId(UUID.randomUUID());
        Material material = buildMaterialWithMessage(author, 1000L);
        UUID materialId = material.getId();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        ServerMember teacherMember =
                ServerMember.builder().server(server).role(ServerRole.TEACHER).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(teacherMember));
        when(serverRepository.findByIdForUpdate(server.getId())).thenReturn(Optional.of(server));
        when(storageService.extractFileKey(material.getFileUrl()))
                .thenReturn("materials/test-server/file.pdf");

        materialService.deleteMaterial(teacherId, materialId);

        verify(materialRepository).delete(material);
    }

    // 教材檔案本身比已用容量還大時，扣減後不該變成負數，應該最低鎖在 0
    @Test
    void deleteMaterial_clampsUsedStorageAtZero_whenFileSizeExceedsRecordedUsage() {
        User author = User.builder().username("Author").email("author@test.com").build();
        author.setId(teacherId);
        Material material = buildMaterialWithMessage(author, 5000L);
        UUID materialId = material.getId();
        server.setUsedStorage(1000L); // 已用容量比這個檔案還小（資料不一致的邊界情況）
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        ServerMember student =
                ServerMember.builder().server(server).role(ServerRole.STUDENT).build();
        when(serverMemberRepository.findByServerIdAndUserId(server.getId(), teacherId))
                .thenReturn(Optional.of(student));
        when(serverRepository.findByIdForUpdate(server.getId())).thenReturn(Optional.of(server));
        when(storageService.extractFileKey(material.getFileUrl()))
                .thenReturn("materials/test-server/file.pdf");

        materialService.deleteMaterial(teacherId, materialId);

        assertThat(server.getUsedStorage()).isEqualTo(0L);
    }

    // ==========================================
    // markAsProcessing
    // ==========================================

    private Material buildMaterialWithStatus(MaterialStatus status) {
        Material material =
                Material.builder()
                        .fileUrl("https://bucket/x.pdf")
                        .fileType("pdf")
                        .originalName("x.pdf")
                        .fileSize(1L)
                        .status(status)
                        .build();
        material.setId(UUID.randomUUID());
        return material;
    }

    // 教材不存在，應該拒絕
    @Test
    void markAsProcessing_throwsMaterialNotFound_whenMaterialDoesNotExist() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findByIdForUpdate(materialId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> materialService.markAsProcessing(materialId))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.MATERIAL_NOT_FOUND.getCode());
    }

    // 已經在處理中了，不該重複觸發（避免同一份教材被排入兩次向量化工作）
    @Test
    void markAsProcessing_throwsAiAssistantProcessing_whenAlreadyProcessing() {
        Material material = buildMaterialWithStatus(MaterialStatus.PROCESSING);
        when(materialRepository.findByIdForUpdate(material.getId()))
                .thenReturn(Optional.of(material));

        assertThatThrownBy(() -> materialService.markAsProcessing(material.getId()))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.AI_ASSISTANT_PROCESSING.getCode());
    }

    // 已經啟用完成了，不該再次觸發處理流程
    @Test
    void markAsProcessing_throwsAiAssistantAlreadyEnabled_whenAlreadyEnabled() {
        Material material = buildMaterialWithStatus(MaterialStatus.ENABLED);
        when(materialRepository.findByIdForUpdate(material.getId()))
                .thenReturn(Optional.of(material));

        assertThatThrownBy(() -> materialService.markAsProcessing(material.getId()))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.AI_ASSISTANT_ALREADY_ENABLED.getCode());
    }

    // 教材狀態是 DISABLED（初始狀態），應該可以正常標記為處理中
    @Test
    void markAsProcessing_succeeds_whenMaterialIsDisabled() {
        Material material = buildMaterialWithStatus(MaterialStatus.DISABLED);
        when(materialRepository.findByIdForUpdate(material.getId()))
                .thenReturn(Optional.of(material));

        materialService.markAsProcessing(material.getId());

        assertThat(material.getStatus()).isEqualTo(MaterialStatus.PROCESSING);
        verify(materialRepository).save(material);
    }
}
