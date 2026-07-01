package com.hys.classcord.quiz.service;

import com.hys.classcord.ai.entity.MaterialChunk;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizService {

    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final MaterialQuestionRepository materialQuestionRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizGenerationJobRepository quizGenerationJobRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final RabbitTemplate rabbitTemplate;
    private final SsePushManager ssePushManager;
    private final MaterialSlicingStrategy slicingStrategy;
    private final QuizJobManager quizJobManager;

    // 注入 Redis 限流，用於全域精確 RPM 限流
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    @Value("classpath:prompts/quiz-single-choice-generation.st")
    private Resource promptResource;

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
        UUID serverId = material.getMessage().getChannel().getServer().getId();
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

        // 5. 派發非同步訊息至 RabbitMQ 進行處理
        QuizGenerationMessage message =
                new QuizGenerationMessage(job.getId(), materialId, count, difficulty);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.QUIZ_EXCHANGE, RabbitMQConfig.ROUTING_KEY_QUIZ_GEN, message);

        log.info("已成功派發異步出題任務到 RabbitMQ: jobId={}, materialId={}", job.getId(), materialId);

        return new QuizJobStatusResponse(job.getId(), materialId, JobStatus.PENDING, null);
    }

    /** 背景非同步執行 AI 出題與 SSE 進度推播 (由 RabbitMQ 監聽者調用，整合 Redis Lua 限流) */
    @Transactional
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

            // 5. 批次寫入資料庫
            materialQuestionRepository.saveAll(questions);

            // 5. 交易正常 Commit：更新為 COMPLETED 並推送最後 SSE
            job.updateStatus(JobStatus.COMPLETED);
            ssePushManager.sendStatusUpdate(
                    jobId, new QuizJobStatusResponse(jobId, materialId, JobStatus.COMPLETED, null));

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
        var outputConverter = new BeanOutputConverter<>(GeneratedQuestionDto.class);
        String formatSpec = outputConverter.getFormat();

        String promptText;
        try {
            promptText = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("無法讀取出題提示詞範本", e);
        }

        ChatClient chatClient = chatClientBuilder.build();
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

    /** 教師/助教：查看教材的題庫列表 (審核用) */
    public List<MaterialQuestionResponse> getMaterialQuestionPool(UUID userId, UUID materialId) {
        Material material =
                materialRepository
                        .findById(materialId)
                        .orElseThrow(
                                () -> new MaterialException(MaterialErrorCode.MATERIAL_NOT_FOUND));

        UUID serverId = material.getMessage().getChannel().getServer().getId();
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

        UUID serverId = question.getMaterial().getMessage().getChannel().getServer().getId();
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
        UUID serverId = material.getMessage().getChannel().getServer().getId();
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, studentId)) {
            throw new QuizException(QuizErrorCode.NOT_SERVER_MEMBER);
        }

        // 2. 檢查可用題庫數量是否大於等於 10 題
        List<MaterialQuestion> activeQuestions =
                materialQuestionRepository.findByMaterialIdAndIsDeletedFalse(materialId);
        if (activeQuestions.size() < 10) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_POOL_QUESTIONS);
        }

        // 3. 隨機抽選 10 題
        List<MaterialQuestion> shuffledList = new ArrayList<>(activeQuestions);
        Collections.shuffle(shuffledList);
        List<MaterialQuestion> selectedQuestions = shuffledList.subList(0, 10);

        // 4. 建立 Quiz 紀錄
        User student =
                userRepository
                        .findById(studentId)
                        .orElseThrow(() -> new MaterialException(MaterialErrorCode.USER_NOT_FOUND));

        Quiz quiz = Quiz.builder().user(student).material(material).build();
        quiz = quizRepository.save(quiz);

        // 5. 建立 QuizQuestions 紀錄 (使用 saveAll 進行批次 Insert，降低連線消耗)
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

        List<QuizQuestion> quizQuestions = quizQuestionRepository.findByQuizId(quizId);
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
    public QuizSubmitResponse getQuizReport(UUID userId, UUID quizId) {
        Quiz quiz =
                quizRepository
                        .findById(quizId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.QUIZ_NOT_FOUND));

        UUID serverId = quiz.getMaterial().getMessage().getChannel().getServer().getId();
        ServerMember member =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, userId)
                        .orElseThrow(() -> new QuizException(QuizErrorCode.NOT_SERVER_MEMBER));

        if (!quiz.getUser().getId().equals(userId) && member.getRole() == ServerRole.STUDENT) {
            throw new QuizException(QuizErrorCode.INSUFFICIENT_PERMISSIONS, "無權訪問此測驗報告");
        }

        List<QuizQuestion> quizQuestions = quizQuestionRepository.findByQuizId(quizId);
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
}
