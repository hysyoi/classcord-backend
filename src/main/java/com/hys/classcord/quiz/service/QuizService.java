package com.hys.classcord.quiz.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.ai.entity.MaterialChunk;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.MaterialChunkRepository;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialErrorCode;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.exception.MaterialException;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.quiz.dto.*;
import com.hys.classcord.quiz.dto.ClassDoubtResponse;
import com.hys.classcord.quiz.dto.ClassWrongQuestionResponse;
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
import com.hys.classcord.quiz.strategy.MaterialSlicingStrategy;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final MaterialQuestionRepository materialQuestionRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizGenerationJobRepository quizGenerationJobRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final AiMessageRepository aiMessageRepository;

    private final ChatClient chatClient;

    private final RabbitTemplate rabbitTemplate;
    private final SsePushManager ssePushManager;
    private final MaterialSlicingStrategy slicingStrategy;
    private final QuizJobManager quizJobManager;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final RedisScript<Long> unlockScript;

    // 業界最佳實踐：預先配置執行緒安全的 ObjectMapper 以便重複利用
    private final ObjectMapper quizObjectMapper =
            new ObjectMapper()
                    .configure(
                            JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(),
                            true)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("classpath:prompts/quiz-single-choice-generation.st")
    private Resource promptResource;

    @Value("classpath:prompts/class-doubt-analysis.st")
    private Resource doubtPromptResource;

    /** 註冊前端的 SSE 訂閱管道 */
    public SseEmitter registerSseStream(UUID jobId) {
        return ssePushManager.register(jobId);
    }

    /** 教師/助教：發起 AI 出題請求 (異步架構，寫入 Job 並派發 MQ 消息) */
    @Transactional
    public QuizJobStatusResponse triggerQuestionsGeneration(
            UUID userId, UUID materialId, int count, String difficulty) {
        // 1. 取得教材與驗證狀態
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        // 是否啟用 AI 助教
        if (material.getStatus() != MaterialStatus.ENABLED) {
            throw new QuizException(QuizErrorCode.MATERIAL_NOT_ENABLED);
        }

        // 2. 校驗教師/TA 權限
        UUID serverId = materialRepository.findServerIdById(materialId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));

        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師與助教(TA)可以管理與生成題庫");
        }

        // 3. 題庫容量上限防護 (防刷防惡意耗能)
        long existingCount =
                materialQuestionRepository.countByMaterialIdAndIsDeletedFalse(materialId);
        if (existingCount + count > 50) {
            throw new QuizException(
                    QuizErrorCode.POOL_LIMIT_EXCEEDED,
                    String.format(
                            "該教材的有效題庫已達上限。目前已有 %d 題，本次出題要求新增 %d 題，將超出上限 50 題。",
                            existingCount, count));
        }

        // 4. 建立出題工作 Job 狀態追蹤 (立刻寫入以防異步讀不到)
        QuizGenerationJob job =
                QuizGenerationJob.builder()
                        .materialId(materialId)
                        .status(JobStatus.PENDING)
                        .build();
        job = quizGenerationJobRepository.saveAndFlush(job);

        // 5. 只有在資料庫交易成功 Commit 後才派發訊息至 RabbitMQ，確保背景任務能夠讀取到正確提交的 Job 狀態
        QuizGenerationMessage message =
                new QuizGenerationMessage(job.getId(), materialId, count, difficulty);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            rabbitTemplate.convertAndSend(
                                    RabbitMQConfig.QUIZ_EXCHANGE,
                                    RabbitMQConfig.ROUTING_KEY_QUIZ_GEN,
                                    message);
                            log.info(
                                    "【事務提交後】已成功派發異步出題任務到 RabbitMQ: jobId={}, materialId={}",
                                    message.jobId(),
                                    message.materialId());
                        }
                    });
        } else {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.QUIZ_EXCHANGE, RabbitMQConfig.ROUTING_KEY_QUIZ_GEN, message);
            log.info(
                    "【無事務環境】已直接派發異步出題任務到 RabbitMQ: jobId={}, materialId={}",
                    message.jobId(),
                    message.materialId());
        }

        return new QuizJobStatusResponse(job.getId(), materialId, JobStatus.PENDING, null);
    }

    /** 背景非同步執行 AI 出題與 SSE 進度推播 (由 RabbitMQ 監聽者調用，整合 Redis Lua 限流) */
    public void executeQuizGenerationBackground(
            UUID jobId, UUID materialId, int count, String difficulty) {
        QuizGenerationJob job = quizGenerationJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("找不到對應的出題 Job 紀錄: {}", jobId);
            return;
        }

        // 1. 在獨立交易中更新狀態為 RUNNING 並推送 SSE
        quizJobManager.updateJobStatusAndPushRequiresNew(
                jobId, materialId, JobStatus.RUNNING, null);

        try {
            Material material =
                    materialRepository
                            .findById(materialId)
                            .orElseThrow(
                                    () ->
                                            new MaterialException(
                                                    MaterialErrorCode.MATERIAL_NOT_FOUND));

            List<MaterialChunk> chunks =
                    materialChunkRepository.findByMaterialId(materialId.toString());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("該教材無切片內容，無法出題，請確認教材是否正常解析");
            }

            log.info(
                    "開始背景出題任務 (Redis 控速): jobId={}, materialId={}, count={}",
                    jobId,
                    materialId,
                    count);

            // 2. 使用策略抽離：呼叫 SlicingStrategy 取得各分段的文字上下文
            List<String> contexts = slicingStrategy.slice(chunks, count);

            // 3. 併行呼叫 Gemini，利用虛擬執行緒提高吞吐量，極速生成考題
            List<GeneratedQuestionDto> dtos;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<GeneratedQuestionDto>> futures =
                        contexts.stream()
                                .map(
                                        context ->
                                                CompletableFuture.supplyAsync(
                                                        () -> {
                                                            // 發送請求前，向 Redis 爭取全域 Rate Limiter 額度許可
                                                            acquireGeminiRateLimitPermit(jobId);
                                                            // 呼叫 Gemini AI
                                                            return generateSingleQuestion(
                                                                    context, difficulty);
                                                        },
                                                        executor))
                                .toList();

                // 等待所有執行緒完成並收集 DTO 結果 (在背景佇列執行緒中阻塞等待)
                dtos = futures.stream().map(CompletableFuture::join).toList();
            }

            // 4. 將生成的 DTO 映射成實體 (在主交易執行緒執行，100% 符合 Hibernate 連線執行緒安全)
            List<MaterialQuestion> questions =
                    dtos.stream()
                            .map(
                                    dto ->
                                            MaterialQuestion.builder()
                                                    .material(material)
                                                    .type(QuestionType.SINGLE_CHOICE)
                                                    .question(dto.question())
                                                    // 防禦性清洗，清除 "A. ", "B. " 前綴，確保資料格式整齊
                                                    .options(sanitizeOptions(dto.options()))
                                                    .correctAnswer(dto.correctAnswer())
                                                    .explanation(dto.explanation())
                                                    .isDeleted(false)
                                                    .build())
                            .toList();

            // 5. 委託給 QuizJobManager 在資料庫事務中進行批次寫庫與狀態更新，徹底避免 Gemini 呼叫時佔用連線
            quizJobManager.saveQuestionsAndCompleteJob(jobId, materialId, questions);

            log.info("AI 背景分段出題成功: jobId={}, 共 {} 題已存入題庫", jobId, count);

        } catch (Exception e) {
            log.error("AI 出題背景生成任務失敗 jobId={}", jobId, e);
            // 6. 發生異常事務回滾：在獨立事務中標記 Job 狀態為 FAILED 並推送錯誤 SSE
            quizJobManager.updateJobStatusAndPushRequiresNew(
                    jobId, materialId, JobStatus.FAILED, e.getMessage());
        }
    }

    /** 防禦性清洗：清除選項文字開頭的 A-D/a-d 英文字母標點符號前綴 */
    private List<String> sanitizeOptions(List<String> rawOptions) {
        if (rawOptions == null) {
            return Collections.emptyList();
        }
        List<String> clean = new ArrayList<>();
        for (String opt : rawOptions) {
            String trimmed = opt.trim();
            // 匹配 A. B. C. D. 或 A: B: C: D: 等開頭 (不分大小寫，排除空格以防誤判)
            if (trimmed.matches("^[A-Da-d][.:].*")) {
                trimmed = trimmed.substring(2).trim();
            }
            clean.add(trimmed);
        }
        return clean;
    }

    /** 藉由 Redis Lua 腳本爭取 Gemini API 限流額度的私有方法 (實作最大重試次數) */
    private void acquireGeminiRateLimitPermit(UUID jobId) {
        String redisKey = "RATE_LIMIT:GEMINI_API";
        int maxAttempts = 10; // 最多嘗試 10 次
        int waitTimeMs = 1000; // 沒拿到許可時每次等待 1 秒
        int limitPerPeriod = 900; // 一分鐘限制 900 次 (為 1K RPM 保留 10% 緩衝)
        int expireSeconds = 60; // 計數週期為 60 秒

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 呼叫 Redis 原子增量與過期時間設置
            Long count =
                    redisTemplate.execute(
                            rateLimitScript,
                            Collections.singletonList(redisKey),
                            String.valueOf(expireSeconds));

            long currentCount = Optional.ofNullable(count).orElse(0L);
            if (currentCount <= limitPerPeriod) {
                // 成功獲取許可，直接返回
                return;
            }

            log.warn(
                    "全站 Gemini API 呼叫頻率已達上限 ({} / 60s)。任務 {} 正在進行第 {} 次重試等待...",
                    limitPerPeriod,
                    jobId,
                    attempt);

            if (attempt == maxAttempts) {
                throw new QuizException(
                        QuizErrorCode.POOL_LIMIT_EXCEEDED, "AI 出題服務排隊繁忙，超限重試次數已達上限，請稍後再試。");
            }

            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("全域限流排隊被中斷", e);
            }
        }
    }

    /** AI 單題生成方法，以結構化 JSON 輸出解析 */
    private GeneratedQuestionDto generateSingleQuestion(String context, String difficulty) {
        var outputConverter =
                new BeanOutputConverter<>(GeneratedQuestionDto.class, quizObjectMapper);
        String formatSpec = outputConverter.getFormat();

        String promptText;
        try {
            promptText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("無法讀取出題提示詞範本", e);
        }

        String response =
                chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(promptText)
                                                .param("context", context)
                                                .param("difficulty", difficulty)
                                                .param("format_spec", formatSpec))
                        .call()
                        .content();

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 服務未回傳任何出題內容");
        }
        return outputConverter.convert(response);
    }

    /** 教師/助教：查看教材對應的完整題庫 (包含解析與選項) */
    @Transactional(readOnly = true)
    public List<MaterialQuestionResponse> getMaterialQuestionPool(UUID userId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        UUID serverId = materialRepository.findServerIdById(materialId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));

        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師與助教(TA)可以查看完整題庫");
        }

        List<MaterialQuestion> questions =
                materialQuestionRepository.findByMaterialIdAndIsDeletedFalse(materialId);
        return questions.stream()
                .map(
                        q ->
                                new MaterialQuestionResponse(
                                        q.getId(),
                                        q.getType(),
                                        q.getQuestion(),
                                        q.getOptions(),
                                        q.getCorrectAnswer(),
                                        q.getExplanation()))
                .toList();
    }

    /** 教師/助教：軟刪除題庫中的考題 (自動保存，無須顯式 save) */
    @Transactional
    public void deleteMaterialQuestion(UUID userId, UUID questionId) {
        MaterialQuestion question =
                materialQuestionRepository
                        .findByIdAndIsDeletedFalse(questionId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.QUESTION_NOT_FOUND));

        UUID serverId = materialQuestionRepository.findServerIdById(questionId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));

        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師與助教(TA)可以刪除考題");
        }

        question.markAsDeleted();
    }

    /** 學生：發起個人自主測驗 (隨機抽選 10 題並隱藏答案，saveAll 批次寫入) */
    @Transactional
    public QuizResponse createQuiz(UUID studentId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        // 1. 驗證是否為本伺服器成員
        UUID serverId = materialRepository.findServerIdById(materialId);
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, studentId)) {
            throw new QuizException(QuizErrorCode.NOT_SERVER_MEMBER);
        }

        // 2. 檢查可用題庫數量是否大於等於 10 題
        List<MaterialQuestion> activeQuestions =
                materialQuestionRepository.findByMaterialIdAndIsDeletedFalse(materialId);
        if (activeQuestions.size() < 10) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_POOL_QUESTIONS);
        }

        // 3. 檢查是否有進行中且未提交的測驗，若有則直接返回該測驗，避免重複創建與洗分漏洞
        List<Quiz> unsubmittedQuizzes =
                quizRepository.findByUserIdAndMaterialIdAndScoreIsNull(studentId, materialId);
        if (!unsubmittedQuizzes.isEmpty()) {
            Quiz quiz = unsubmittedQuizzes.get(0);
            quiz.updateCreatedAt(java.time.Instant.now());
            quiz = quizRepository.save(quiz);

            List<QuizQuestion> quizQuestions =
                    quizQuestionRepository.findWithQuestionsByQuizId(quiz.getId());
            List<QuizQuestionResponse> questionResponses = new ArrayList<>();
            for (QuizQuestion qq : quizQuestions) {
                MaterialQuestion q = qq.getQuestion();
                questionResponses.add(
                        new QuizQuestionResponse(
                                qq.getId(),
                                q.getId(),
                                q.getType(),
                                q.getQuestion(),
                                q.getOptions()));
            }
            return new QuizResponse(
                    quiz.getId(), material.getId(), null, quiz.getCreatedAt(), questionResponses);
        }

        // 4. 隨機抽選 10 題
        List<MaterialQuestion> shuffledList = new ArrayList<>(activeQuestions);
        Collections.shuffle(shuffledList);
        List<MaterialQuestion> selectedQuestions = shuffledList.subList(0, 10);

        // 5. 建立 Quiz 紀錄
        User student =
                userRepository
                        .findById(studentId)
                        .orElseThrow(() -> new MaterialException(MaterialErrorCode.USER_NOT_FOUND));

        Quiz quiz = Quiz.builder().user(student).material(material).build();
        quiz = quizRepository.save(quiz);

        // 6. 建立 QuizQuestions 紀錄 (使用 saveAll 進行批次 Insert，降低連線消耗)
        List<QuizQuestion> quizQuestions = new ArrayList<>();
        for (MaterialQuestion q : selectedQuestions) {
            quizQuestions.add(QuizQuestion.builder().quiz(quiz).question(q).build());
        }
        List<QuizQuestion> savedQuizQuestions = quizQuestionRepository.saveAll(quizQuestions);

        List<QuizQuestionResponse> questionResponses = new ArrayList<>();
        for (QuizQuestion qq : savedQuizQuestions) {
            MaterialQuestion q = qq.getQuestion();
            questionResponses.add(
                    new QuizQuestionResponse(
                            qq.getId(), q.getId(), q.getType(), q.getQuestion(), q.getOptions()));
        }

        return new QuizResponse(
                quiz.getId(), material.getId(), null, quiz.getCreatedAt(), questionResponses);
    }

    /** 學生：提交作答答案並進行評估計分 (自動保存，無須顯式 save) */
    @Transactional
    public QuizSubmitResponse submitQuiz(UUID studentId, UUID quizId, QuizSubmitRequest request) {
        Quiz quiz =
                quizRepository
                        .findById(quizId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.QUIZ_NOT_FOUND));

        if (!quiz.getUser().getId().equals(studentId)) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "無權限提交此測驗");
        }

        if (quiz.getScore() != null) {
            throw new QuizException(QuizErrorCode.QUIZ_ALREADY_SUBMITTED);
        }

        List<QuizQuestion> quizQuestions = quizQuestionRepository.findWithQuestionsByQuizId(quizId);
        int correctCount = 0;
        List<QuizQuestionReviewResponse> reviews = new ArrayList<>();

        for (QuizQuestion qq : quizQuestions) {
            List<String> studentAns = request.answers().get(qq.getId());
            if (studentAns == null) {
                studentAns = Collections.emptyList();
            }

            // 判題判定：目前單選題以 List 陣列內容比對 (順便防呆防大小寫空隙)
            boolean isCorrect = false;
            MaterialQuestion mq = qq.getQuestion();
            if (mq.getType() == QuestionType.SINGLE_CHOICE) {
                isCorrect =
                        studentAns.size() == mq.getCorrectAnswer().size()
                                && new HashSet<>(studentAns).containsAll(mq.getCorrectAnswer());
            }

            qq.submitAnswer(studentAns, isCorrect);

            if (isCorrect) {
                correctCount++;
            }

            reviews.add(
                    new QuizQuestionReviewResponse(
                            qq.getId(),
                            mq.getId(),
                            mq.getType(),
                            mq.getQuestion(),
                            mq.getOptions(),
                            mq.getCorrectAnswer(),
                            studentAns,
                            isCorrect,
                            mq.getExplanation()));
        }

        // 計算分數 (一題 10 分，總共 10 題 = 100 分)
        int finalScore = correctCount * 10;
        quiz.updateScore(finalScore);

        return new QuizSubmitResponse(
                quiz.getId(), quiz.getMaterial().getId(), finalScore, quiz.getCreatedAt(), reviews);
    }

    /** 學生/教師：查看單次測驗的評估報告與歷史明細 */
    @Transactional(readOnly = true)
    public QuizSubmitResponse getQuizReport(UUID userId, UUID quizId) {
        Quiz quiz =
                quizRepository
                        .findById(quizId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.QUIZ_NOT_FOUND));

        UUID serverId = quizRepository.findServerIdById(quizId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));

        if (!quiz.getUser().getId().equals(userId) && member.getRole() == ServerRole.STUDENT) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "無權訪問此測驗報告");
        }

        List<QuizQuestion> quizQuestions = quizQuestionRepository.findWithQuestionsByQuizId(quizId);
        List<QuizQuestionReviewResponse> reviews =
                quizQuestions.stream()
                        .map(
                                qq -> {
                                    MaterialQuestion mq = qq.getQuestion();
                                    return new QuizQuestionReviewResponse(
                                            qq.getId(),
                                            mq.getId(),
                                            mq.getType(),
                                            mq.getQuestion(),
                                            mq.getOptions(),
                                            mq.getCorrectAnswer(),
                                            qq.getUserAnswer() != null
                                                    ? qq.getUserAnswer()
                                                    : Collections.emptyList(),
                                            qq.getIsCorrect() != null ? qq.getIsCorrect() : false,
                                            mq.getExplanation());
                                })
                        .toList();

        return new QuizSubmitResponse(
                quiz.getId(),
                quiz.getMaterial().getId(),
                quiz.getScore(),
                quiz.getCreatedAt(),
                reviews);
    }

    /** 學生：查詢該教材自己所有的歷史測驗紀錄列表 */
    @Transactional(readOnly = true)
    public List<QuizResponse> getUserMaterialQuizzes(UUID userId, UUID materialId) {
        UUID serverId = materialRepository.findServerIdById(materialId);
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            throw new QuizException(QuizErrorCode.NOT_SERVER_MEMBER);
        }

        List<Quiz> quizzes =
                quizRepository.findByUserIdAndMaterialIdAndScoreIsNotNullOrderByCreatedAtDesc(
                        userId, materialId);
        return quizzes.stream()
                .map(
                        q ->
                                new QuizResponse(
                                        q.getId(),
                                        q.getMaterial().getId(),
                                        q.getScore(),
                                        q.getCreatedAt(),
                                        java.util.Collections.emptyList()))
                .toList();
    }

    /** 教師/助教：獲取班級錯題統計分析 */
    @Transactional(readOnly = true)
    public List<ClassWrongQuestionResponse> getClassWrongQuestionAnalysis(
            UUID userId, UUID materialId) {
        // 1. 驗證權限 (限教師與助教)
        UUID serverId = materialRepository.findServerIdById(materialId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));
        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師與助教(TA)可以查看班級分析");
        }

        // 2. 撈取該教材的所有題庫題目
        List<MaterialQuestion> questions =
                materialQuestionRepository.findByMaterialIdAndIsDeletedFalse(materialId);

        // 3. 撈取該教材的所有已提交作答記錄 (輕量化投影以防 OOM，限制最多統計最近 10,000 筆答題紀錄)
        List<Object[]> rawRecords =
                quizQuestionRepository.findLightweightSubmittedQuestionsByMaterialId(
                        materialId, org.springframework.data.domain.Limit.of(10000));

        // 4. 將答題記錄按問題 ID 分組
        Map<UUID, List<LightweightRecord>> qqGroup = new HashMap<>();
        for (Object[] row : rawRecords) {
            UUID questionId = (UUID) row[0];
            Boolean isCorrect = (Boolean) row[1];
            @SuppressWarnings("unchecked")
            List<String> userAnswer = (List<String>) row[2];

            qqGroup.computeIfAbsent(questionId, k -> new ArrayList<>())
                    .add(new LightweightRecord(isCorrect, userAnswer));
        }

        // 5. 開始統計計算每一題
        List<ClassWrongQuestionResponse> analysisList = new ArrayList<>();
        for (MaterialQuestion mq : questions) {
            List<LightweightRecord> history =
                    qqGroup.getOrDefault(mq.getId(), java.util.Collections.emptyList());
            int totalAttempts = history.size();
            int wrongAttempts = 0;
            Map<String, Integer> optionDistribution = new HashMap<>();

            // 初始化選項分佈計數器 (A, B, C, D)
            for (String opt : List.of("A", "B", "C", "D")) {
                optionDistribution.put(opt, 0);
            }

            for (LightweightRecord qq : history) {
                if (Boolean.FALSE.equals(qq.isCorrect())) {
                    wrongAttempts++;
                }
                if (qq.userAnswer() != null) {
                    for (String ans : qq.userAnswer()) {
                        if (ans != null && !ans.isBlank()) {
                            optionDistribution.put(
                                    ans, optionDistribution.getOrDefault(ans, 0) + 1);
                        }
                    }
                }
            }

            double errorRate = totalAttempts > 0 ? (double) wrongAttempts / totalAttempts : 0.0;

            analysisList.add(
                    new ClassWrongQuestionResponse(
                            mq.getId(),
                            mq.getType(),
                            mq.getQuestion(),
                            mq.getOptions(),
                            mq.getCorrectAnswer(),
                            mq.getExplanation(),
                            totalAttempts,
                            wrongAttempts,
                            errorRate,
                            optionDistribution));
        }

        // 6. 依錯誤率降序排列，再依錯誤人數降序排列
        analysisList.sort(
                (a, b) -> {
                    int cmp = Double.compare(b.errorRate(), a.errorRate());
                    if (cmp != 0) return cmp;
                    return Integer.compare(b.wrongAttempts(), a.wrongAttempts());
                });

        return analysisList;
    }

    /** 教師/助教：獲取 AI 班級疑問分析 (彙整最近 50 筆學生向 AI 助教的發問對話) */
    @Transactional(readOnly = true)
    public ClassDoubtResponse getClassDoubtAnalysis(
            UUID userId, UUID materialId, boolean regenerate) {
        // 1. 驗證權限 (限教師與助教)
        UUID serverId = materialRepository.findServerIdById(materialId);
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));
        if (member.getRole() != ServerRole.TEACHER && member.getRole() != ServerRole.TA) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "只有教師與助教(TA)可以查看班級分析");
        }

        // 新增快取、鎖、頻率key
        String cacheKey = "doubt_analysis:cache:" + materialId;
        String lockKey = "doubt_analysis:lock:" + materialId;
        String rateLimitKey = "doubt_analysis:rate_limit:" + materialId;

        // 2. 檢查是否正在更新分析中 (若是，直接拋出 409 Conflict)
        Boolean isLocked = redisTemplate.hasKey(lockKey);
        if (Boolean.TRUE.equals(isLocked)) {
            throw new QuizException(QuizErrorCode.AI_ANALYSIS_IN_PROGRESS);
        }

        // 3. 檢查快取
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);

        if (cachedJson != null) {
            if (!regenerate) {
                try {
                    ClassDoubtResponse cachedResult =
                            quizObjectMapper.readValue(cachedJson, ClassDoubtResponse.class);
                    List<String> userMessages =
                            aiMessageRepository.findUserMessagesByMaterialId(
                                    materialId, org.springframework.data.domain.Limit.of(50));
                    return new ClassDoubtResponse(userMessages.size(), cachedResult.themes());
                } catch (Exception e) {
                    log.error("解析 Redis 快取失敗，將重新分析", e);
                }
            }
        } else {
            if (!regenerate) {
                throw new QuizException(QuizErrorCode.AI_ANALYSIS_NOT_FOUND);
            }
        }

        // 💡 效能與資源保護：在加鎖與呼叫 AI 前，先拉取與檢查學生的提問紀錄。
        // 若學生無任何提問紀錄，則直接回傳空主題，避免浪費 AI Token 或引發併發鎖與頻率限制開銷。
        List<String> userMessages =
                aiMessageRepository.findUserMessagesByMaterialId(
                        materialId, org.springframework.data.domain.Limit.of(50));
        if (userMessages.isEmpty()) {
            return new ClassDoubtResponse(0, java.util.Collections.emptyList());
        }

        boolean lockAcquired = false;
        String lockValue = java.util.UUID.randomUUID().toString();
        try {
            // 4. 準備重新分析或初次分析：進行原子加鎖 (防併發)
            // 業界最佳實踐：使用 setIfAbsent (SETNX) 一步完成「檢查與上鎖」的原子操作，並以隨機 UUID 作為識別碼
            Boolean isLockAcquired =
                    redisTemplate
                            .opsForValue()
                            .setIfAbsent(
                                    lockKey, lockValue, 5, java.util.concurrent.TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(isLockAcquired)) {
                throw new QuizException(QuizErrorCode.AI_ANALYSIS_IN_PROGRESS);
            }
            lockAcquired = true;

            // 雙重檢查鎖 (Double-Checked Locking) 最佳實踐：
            // 當成功取得併發鎖後，再次確認 Redis 快取是否已被前一個執行緒產出並寫入。
            // 這能完美防範「當鎖因超時被釋放、前一線程寫完快取後，後續線程又重複呼叫 AI」的資源浪費邊界。
            if (!regenerate) {
                String doubleCheckCache = redisTemplate.opsForValue().get(cacheKey);
                if (doubleCheckCache != null) {
                    try {
                        ClassDoubtResponse cachedResult =
                                quizObjectMapper.readValue(
                                        doubleCheckCache, ClassDoubtResponse.class);
                        return new ClassDoubtResponse(userMessages.size(), cachedResult.themes());
                    } catch (Exception e) {
                        log.error("雙重檢查中解析快取失敗，將繼續執行 AI 分析", e);
                    }
                }
            }

            // 5. 檢查 Rate Limit (一天一次)
            Boolean isRateLimited = redisTemplate.hasKey(rateLimitKey);
            if (Boolean.TRUE.equals(isRateLimited)) {
                throw new QuizException(QuizErrorCode.AI_ANALYSIS_RATE_LIMIT);
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < userMessages.size(); i++) {
                sb.append(i + 1).append(". ").append(userMessages.get(i)).append("\n");
            }
            String userMessagesText = sb.toString();

            // 設定 Spring AI BeanOutputConverter 結構化 JSON 輸出轉換器 (重複使用已配置之 quizObjectMapper)
            var outputConverter =
                    new BeanOutputConverter<>(ClassDoubtResponse.class, quizObjectMapper);
            String formatSpec = outputConverter.getFormat();

            // 讀取疑問分析 prompt 範本
            String promptText;
            try {
                promptText = doubtPromptResource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("無法加載 AI 班級疑問分析提示詞範本", e);
                throw new IllegalStateException("無法讀取疑問分析提示詞範本", e);
            }

            // 呼叫 Gemini AI 進行分析
            String response =
                    chatClient
                            .prompt()
                            .user(
                                    u ->
                                            u.text(promptText)
                                                    .param("userMessages", userMessagesText)
                                                    .param("format_spec", formatSpec))
                            .call()
                            .content();

            if (response == null || response.isBlank()) {
                throw new QuizException(QuizErrorCode.AI_ANALYSIS_FAILED, "AI 服務未回傳任何疑問分析內容");
            }

            ClassDoubtResponse analysisResult = outputConverter.convert(response);
            if (analysisResult == null) {
                throw new QuizException(
                        QuizErrorCode.AI_ANALYSIS_FAILED, "無法將 AI 回傳內容轉換為 ClassDoubtResponse 格式");
            }

            // 寫入快取
            String jsonResult = quizObjectMapper.writeValueAsString(analysisResult);
            redisTemplate.opsForValue().set(cacheKey, jsonResult);

            // 設定 24 小時限流
            redisTemplate
                    .opsForValue()
                    .set(rateLimitKey, "true", 24, java.util.concurrent.TimeUnit.HOURS);

            return new ClassDoubtResponse(userMessages.size(), analysisResult.themes());

        } catch (QuizException qe) {
            throw qe;
        } catch (Exception e) {
            log.error("AI 分析疑問失敗", e);
            throw new QuizException(QuizErrorCode.AI_ANALYSIS_FAILED, "AI 分析失敗：" + e.getMessage());
        } finally {
            // 解鎖 (使用 Spring Bean 載入之 Lua 腳本進行原子化比對，防止因過期而誤刪他人的鎖)
            if (lockAcquired) {
                redisTemplate.execute(
                        unlockScript, java.util.Collections.singletonList(lockKey), lockValue);
            }
        }
    }

    private record LightweightRecord(Boolean isCorrect, List<String> userAnswer) {}
}
