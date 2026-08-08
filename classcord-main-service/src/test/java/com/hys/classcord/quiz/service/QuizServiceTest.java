package com.hys.classcord.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.client.QuizAiClient;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.quiz.dto.QuizSubmitRequest;
import com.hys.classcord.quiz.entity.MaterialQuestion;
import com.hys.classcord.quiz.entity.Quiz;
import com.hys.classcord.quiz.entity.QuizGenerationJob;
import com.hys.classcord.quiz.entity.QuizQuestion;
import com.hys.classcord.quiz.enums.JobStatus;
import com.hys.classcord.quiz.enums.QuestionType;
import com.hys.classcord.quiz.enums.QuizErrorCode;
import com.hys.classcord.quiz.exception.QuizException;
import com.hys.classcord.quiz.repository.MaterialQuestionRepository;
import com.hys.classcord.quiz.repository.QuizGenerationJobRepository;
import com.hys.classcord.quiz.repository.QuizQuestionRepository;
import com.hys.classcord.quiz.repository.QuizRepository;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

// 純單元測試：不啟動 Spring context、不連真的 Redis/DB。
// 只測 submitQuiz（評分邏輯）與 triggerQuestionsGeneration（權限＋題庫上限邏輯），
// 因為這兩個方法在沒有真正交易的情況下也能被完整測到（isActualTransactionActive() 在
// 單元測試環境下天生就是 false，會直接走同步派發訊息那條路徑，不需要額外模擬交易同步機制）。
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    @Mock private MaterialRepository materialRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServerMemberRepository serverMemberRepository;
    @Mock private MaterialQuestionRepository materialQuestionRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private QuizQuestionRepository quizQuestionRepository;
    @Mock private QuizGenerationJobRepository quizGenerationJobRepository;
    @Mock private QuizAiClient quizAiClient;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private SsePushManager ssePushManager;
    @Mock private QuizJobManager quizJobManager;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisScript<Long> rateLimitScript;
    @Mock private RedisScript<Long> unlockScript;

    private QuizService quizService;

    @BeforeEach
    void setUp() {
        quizService =
                new QuizService(
                        materialRepository,
                        userRepository,
                        serverMemberRepository,
                        materialQuestionRepository,
                        quizRepository,
                        quizQuestionRepository,
                        quizGenerationJobRepository,
                        quizAiClient,
                        rabbitTemplate,
                        ssePushManager,
                        quizJobManager,
                        redisTemplate,
                        rateLimitScript,
                        unlockScript);
    }

    // ==========================================
    // submitQuiz
    // ==========================================

    private MaterialQuestion buildQuestion(List<String> correctAnswer) {
        MaterialQuestion mq =
                MaterialQuestion.builder()
                        .type(QuestionType.SINGLE_CHOICE)
                        .question("測試題目")
                        .options(List.of("A", "B", "C", "D"))
                        .correctAnswer(correctAnswer)
                        .build();
        mq.setId(UUID.randomUUID());
        return mq;
    }

    // 找不到測驗紀錄，應該拒絕
    @Test
    void submitQuiz_throwsQuizNotFound_whenQuizDoesNotExist() {
        UUID quizId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                quizService.submitQuiz(
                                        UUID.randomUUID(), quizId, new QuizSubmitRequest(Map.of())))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.QUIZ_NOT_FOUND.getCode());
    }

    // 別人的測驗，不能代為提交
    @Test
    void submitQuiz_throwsInsufficientPermissions_whenNotTheOwner() {
        UUID quizId = UUID.randomUUID();
        User owner = User.builder().username("Owner").email("owner@test.com").build();
        owner.setId(UUID.randomUUID());
        Quiz quiz = Quiz.builder().user(owner).build();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));

        UUID someoneElse = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                quizService.submitQuiz(
                                        someoneElse, quizId, new QuizSubmitRequest(Map.of())))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.INSUFFICIENT_PERMISSIONS.getCode());
    }

    // 已經提交過的測驗（score 不為 null），不能重複提交，防止洗分
    @Test
    void submitQuiz_throwsQuizAlreadySubmitted_whenAlreadyHasScore() {
        UUID quizId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User student = User.builder().username("Student").email("student@test.com").build();
        student.setId(studentId);
        Quiz quiz = Quiz.builder().user(student).score(80).build();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));

        assertThatThrownBy(
                        () ->
                                quizService.submitQuiz(
                                        studentId, quizId, new QuizSubmitRequest(Map.of())))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.QUIZ_ALREADY_SUBMITTED.getCode());
    }

    // 答對所有題目應該得滿分，且順序不影響判定（用 Set 比對內容而非陣列順序）
    @Test
    void submitQuiz_scoresFullMarks_whenAllAnswersCorrectRegardlessOfOrder() {
        UUID quizId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User student = User.builder().username("Student").email("student@test.com").build();
        student.setId(studentId);
        Material material =
                Material.builder()
                        .fileUrl("x")
                        .fileType("pdf")
                        .originalName("x")
                        .fileSize(1L)
                        .build();
        material.setId(UUID.randomUUID());
        Quiz quiz = Quiz.builder().user(student).material(material).build();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));

        MaterialQuestion mq = buildQuestion(List.of("A", "B"));
        QuizQuestion qq = QuizQuestion.builder().quiz(quiz).question(mq).build();
        qq.setId(UUID.randomUUID());
        when(quizQuestionRepository.findWithQuestionsByQuizId(quizId)).thenReturn(List.of(qq));

        // 順序故意跟正確答案相反，驗證判題不是比對陣列順序
        QuizSubmitRequest request = new QuizSubmitRequest(Map.of(qq.getId(), List.of("B", "A")));

        var response = quizService.submitQuiz(studentId, quizId, request);

        assertThat(response.score()).isEqualTo(10);
        assertThat(response.reviews().get(0).isCorrect()).isTrue();
    }

    // 答錯（答案數量不吻合）不該給分
    @Test
    void submitQuiz_scoresZero_whenAnswerDoesNotMatch() {
        UUID quizId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User student = User.builder().username("Student").email("student@test.com").build();
        student.setId(studentId);
        Material material =
                Material.builder()
                        .fileUrl("x")
                        .fileType("pdf")
                        .originalName("x")
                        .fileSize(1L)
                        .build();
        material.setId(UUID.randomUUID());
        Quiz quiz = Quiz.builder().user(student).material(material).build();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));

        MaterialQuestion mq = buildQuestion(List.of("A"));
        QuizQuestion qq = QuizQuestion.builder().quiz(quiz).question(mq).build();
        qq.setId(UUID.randomUUID());
        when(quizQuestionRepository.findWithQuestionsByQuizId(quizId)).thenReturn(List.of(qq));

        QuizSubmitRequest request = new QuizSubmitRequest(Map.of(qq.getId(), List.of("B")));

        var response = quizService.submitQuiz(studentId, quizId, request);

        assertThat(response.score()).isEqualTo(0);
        assertThat(response.reviews().get(0).isCorrect()).isFalse();
    }

    // 學生根本沒回答某一題（Map 裡完全沒有這個 key），應該視為答錯，而不是丟 NullPointerException
    @Test
    void submitQuiz_treatsUnansweredQuestionAsWrong_withoutThrowing() {
        UUID quizId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User student = User.builder().username("Student").email("student@test.com").build();
        student.setId(studentId);
        Material material =
                Material.builder()
                        .fileUrl("x")
                        .fileType("pdf")
                        .originalName("x")
                        .fileSize(1L)
                        .build();
        material.setId(UUID.randomUUID());
        Quiz quiz = Quiz.builder().user(student).material(material).build();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));

        MaterialQuestion mq = buildQuestion(List.of("A"));
        QuizQuestion qq = QuizQuestion.builder().quiz(quiz).question(mq).build();
        qq.setId(UUID.randomUUID());
        when(quizQuestionRepository.findWithQuestionsByQuizId(quizId)).thenReturn(List.of(qq));

        QuizSubmitRequest request = new QuizSubmitRequest(Map.of()); // 完全沒有作答紀錄

        var response = quizService.submitQuiz(studentId, quizId, request);

        assertThat(response.score()).isEqualTo(0);
        assertThat(response.reviews().get(0).isCorrect()).isFalse();
    }

    // ==========================================
    // triggerQuestionsGeneration
    // ==========================================

    private Material buildEnabledMaterial() {
        Material material =
                Material.builder()
                        .fileUrl("x")
                        .fileType("pdf")
                        .originalName("x")
                        .fileSize(1L)
                        .status(MaterialStatus.ENABLED)
                        .build();
        material.setId(UUID.randomUUID());
        return material;
    }

    // 教材找不到，應該拒絕
    @Test
    void triggerQuestionsGeneration_throwsMaterialNotFound_whenMaterialDoesNotExist() {
        UUID materialId = UUID.randomUUID();
        when(materialRepository.findById(materialId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                quizService.triggerQuestionsGeneration(
                                        UUID.randomUUID(), materialId, 5, "MEDIUM"))
                .isInstanceOf(MaterialException.class)
                .extracting("code")
                .isEqualTo(MaterialErrorCode.MATERIAL_NOT_FOUND.getCode());
    }

    // 教材還沒啟用 AI 助教，不能出題
    @Test
    void triggerQuestionsGeneration_throwsMaterialNotEnabled_whenAiAssistantNotEnabled() {
        UUID materialId = UUID.randomUUID();
        Material material =
                Material.builder()
                        .fileUrl("x")
                        .fileType("pdf")
                        .originalName("x")
                        .fileSize(1L)
                        .status(MaterialStatus.DISABLED)
                        .build();
        material.setId(materialId);
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));

        assertThatThrownBy(
                        () ->
                                quizService.triggerQuestionsGeneration(
                                        UUID.randomUUID(), materialId, 5, "MEDIUM"))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.MATERIAL_NOT_ENABLED.getCode());
    }

    // 不是該伺服器成員，應該拒絕
    @Test
    void triggerQuestionsGeneration_throwsNotServerMember_whenUserIsNotAMember() {
        UUID materialId = UUID.randomUUID();
        Material material = buildEnabledMaterial();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        UUID serverId = UUID.randomUUID();
        when(materialRepository.findServerIdById(materialId)).thenReturn(serverId);
        UUID userId = UUID.randomUUID();
        when(serverMemberRepository.findByServerIdAndUserId(serverId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                quizService.triggerQuestionsGeneration(
                                        userId, materialId, 5, "MEDIUM"))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.NOT_SERVER_MEMBER.getCode());
    }

    // 學生沒有出題權限
    @Test
    void triggerQuestionsGeneration_throwsInsufficientPermissions_whenUserIsStudent() {
        UUID materialId = UUID.randomUUID();
        Material material = buildEnabledMaterial();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        UUID serverId = UUID.randomUUID();
        when(materialRepository.findServerIdById(materialId)).thenReturn(serverId);
        UUID userId = UUID.randomUUID();
        ServerMember student = ServerMember.builder().role(ServerRole.STUDENT).build();
        when(serverMemberRepository.findByServerIdAndUserId(serverId, userId))
                .thenReturn(Optional.of(student));

        assertThatThrownBy(
                        () ->
                                quizService.triggerQuestionsGeneration(
                                        userId, materialId, 5, "MEDIUM"))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.INSUFFICIENT_PERMISSIONS.getCode());
    }

    // 題庫已有 48 題，這次又要求生成 5 題，48+5=53 超過 50 題上限，應該拒絕
    @Test
    void triggerQuestionsGeneration_throwsPoolLimitExceeded_whenOverCapacity() {
        UUID materialId = UUID.randomUUID();
        Material material = buildEnabledMaterial();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        UUID serverId = UUID.randomUUID();
        when(materialRepository.findServerIdById(materialId)).thenReturn(serverId);
        UUID userId = UUID.randomUUID();
        ServerMember teacher = ServerMember.builder().role(ServerRole.TEACHER).build();
        when(serverMemberRepository.findByServerIdAndUserId(serverId, userId))
                .thenReturn(Optional.of(teacher));
        when(materialQuestionRepository.countByMaterialIdAndIsDeletedFalse(materialId))
                .thenReturn(48L);

        assertThatThrownBy(
                        () ->
                                quizService.triggerQuestionsGeneration(
                                        userId, materialId, 5, "MEDIUM"))
                .isInstanceOf(QuizException.class)
                .extracting("code")
                .isEqualTo(QuizErrorCode.POOL_LIMIT_EXCEEDED.getCode());

        verify(quizGenerationJobRepository, never()).saveAndFlush(any());
    }

    // 剛好卡在上限邊界（45+5=50，不超過），應該放行，並派發 Job 給 RabbitMQ
    @Test
    void triggerQuestionsGeneration_succeeds_whenExactlyAtCapacityLimit() {
        UUID materialId = UUID.randomUUID();
        Material material = buildEnabledMaterial();
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        UUID serverId = UUID.randomUUID();
        when(materialRepository.findServerIdById(materialId)).thenReturn(serverId);
        UUID userId = UUID.randomUUID();
        ServerMember ta = ServerMember.builder().role(ServerRole.TA).build();
        when(serverMemberRepository.findByServerIdAndUserId(serverId, userId))
                .thenReturn(Optional.of(ta));
        when(materialQuestionRepository.countByMaterialIdAndIsDeletedFalse(materialId))
                .thenReturn(45L);
        when(quizGenerationJobRepository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            QuizGenerationJob job = invocation.getArgument(0);
                            job.setId(UUID.randomUUID());
                            return job;
                        });

        var response = quizService.triggerQuestionsGeneration(userId, materialId, 5, "MEDIUM");

        assertThat(response.materialId()).isEqualTo(materialId);
        assertThat(response.status()).isEqualTo(JobStatus.PENDING);
        // 單元測試裡沒有真正的交易在跑，isActualTransactionActive() 是 false，
        // 會直接走「無事務環境」那條路徑同步派發訊息，而不是註冊 afterCommit 回呼
        // RabbitTemplate.convertAndSend 有多個 3 參數多載，沒型別提示的 any() 會解析到錯的多載，
        // 用 any(Object.class) 明確指定（踩過同類型的坑，見 BaseIntegrationTest 附近的討論）
        verify(rabbitTemplate)
                .convertAndSend(
                        eq(RabbitMQConfig.QUIZ_EXCHANGE),
                        eq(RabbitMQConfig.ROUTING_KEY_QUIZ_GEN),
                        any(Object.class));
    }
}
