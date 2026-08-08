package com.hys.classcord.quiz.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.client.QuizAiClient;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.quiz.dto.ClassDoubtResponse;
import com.hys.classcord.quiz.dto.DoubtTheme;
import com.hys.classcord.quiz.dto.QuestionExplanation;
import com.hys.classcord.quiz.dto.QuizSubmitRequest;
import com.hys.classcord.quiz.entity.MaterialQuestion;
import com.hys.classcord.quiz.enums.QuestionType;
import com.hys.classcord.quiz.repository.MaterialQuestionRepository;
import com.hys.classcord.quiz.repository.QuizQuestionRepository;
import com.hys.classcord.quiz.repository.QuizRepository;
import com.hys.classcord.quiz.service.QuizService;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@DirtiesContext
public class QuizIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ServerMemberRepository serverMemberRepository;
    @Autowired private MaterialQuestionRepository materialQuestionRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizQuestionRepository quizQuestionRepository;
    @Autowired private QuizService quizService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @MockBean private QuizAiClient quizAiClient;

    private String teacherToken;
    private String studentToken;
    private Material testMaterial;

    @BeforeEach
    void setUp() {
        // 清理資料庫以防干擾（QuizJobManager 用 REQUIRES_NEW 開新交易寫入，
        // 不受測試的 @Transactional rollback 保護，需手動清除）
        quizQuestionRepository.deleteAll();
        quizRepository.deleteAll();
        materialQuestionRepository.deleteAll();

        // 1. 建立使用者與 Token
        User teacher =
                userRepository.save(
                        User.builder().username("QuizTeacher").email("q-teacher@test.com").build());
        User student =
                userRepository.save(
                        User.builder().username("QuizStudent").email("q-student@test.com").build());

        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());
        studentToken = "Bearer " + jwtUtils.generateToken(student.getId(), student.getEmail());

        // 2. 建立班級伺服器、頻道與教材
        Server server =
                serverRepository.save(Server.builder().name("Quiz Class").owner(teacher).build());
        Channel channel =
                channelRepository.save(
                        Channel.builder()
                                .name("講義專區")
                                .server(server)
                                .type(ChannelType.MATERIAL)
                                .position(0)
                                .build());
        Message message =
                messageRepository.save(
                        Message.builder().channel(channel).user(teacher).content("上傳教材囉").build());

        testMaterial =
                materialRepository.save(
                        Material.builder()
                                .message(message)
                                .fileUrl("https://quiz-bucket.s3.amazonaws.com/java.pdf")
                                .fileType("pdf")
                                .originalName("java-basics.pdf")
                                .fileSize(1024L)
                                .status(MaterialStatus.ENABLED)
                                .build());

        // 3. 設定伺服器成員身分 (Teacher/Student)
        serverMemberRepository.save(
                ServerMember.builder()
                        .server(server)
                        .user(teacher)
                        .role(ServerRole.TEACHER)
                        .build());
        serverMemberRepository.save(
                ServerMember.builder()
                        .server(server)
                        .user(student)
                        .role(ServerRole.STUDENT)
                        .build());
    }

    // 教師發起 AI 出題，應非同步回傳 PENDING 狀態，題目生成後可查詢到題庫內容
    @Test
    void testGenerateQuestions_Success() throws Exception {
        // 1. 教師發起出題 (預期異步返回 PENDING 狀態與 jobId)
        String mvcResult =
                mockMvc.perform(
                                post("/v1/materials/"
                                                + testMaterial.getId()
                                                + "/questions/generate")
                                        .header("Authorization", teacherToken)
                                        .param("count", "3")
                                        .param("difficulty", "MEDIUM"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.jobId").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID jobId = UUID.fromString(objectMapper.readTree(mvcResult).get("jobId").asText());
        assertNotNull(jobId);

        // 2. 模擬已生成的題目入庫 (3 筆)
        for (int i = 1; i <= 3; i++) {
            materialQuestionRepository.save(
                    MaterialQuestion.builder()
                            .material(testMaterial)
                            .question(i == 1 ? "Java 的 class 關鍵字用來做什麼？" : "測試題目 " + i)
                            .type(QuestionType.SINGLE_CHOICE)
                            .options(List.of("A. 宣告類別", "B. 宣告介面", "C. 宣告變數", "D. 宣告常數"))
                            .correctAnswer(List.of("A"))
                            .explanation(
                                    new QuestionExplanation(
                                            "本題測驗類別基本宣告。",
                                            Map.of(
                                                    "A", "正確，class 為類別宣告關鍵字。",
                                                    "B", "錯誤，介面應使用 interface。",
                                                    "C", "錯誤，宣告變數使用型別名稱。",
                                                    "D", "錯誤，常數宣告使用 final。")))
                            .build());
        }

        // 3. 驗證題庫可供教師查詢
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/questions")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Java 的 class 關鍵字用來做什麼？"))
                .andExpect(jsonPath("$[0].correctAnswer[0]").value("A"))
                .andExpect(jsonPath("$[0].explanation.options.B").value("錯誤，介面應使用 interface。"));

        // 驗證題庫確實寫入 3 筆
        long count =
                materialQuestionRepository.countByMaterialIdAndIsDeletedFalse(testMaterial.getId());
        assertEquals(3, count);
    }

    // 學生沒有出題權限，發起出題應被拒絕
    @Test
    void testGenerateQuestions_InsufficientPermission() throws Exception {
        // 學生嘗試發起出題，預期 403 Forbidden
        mockMvc.perform(
                        post("/v1/materials/" + testMaterial.getId() + "/questions/generate")
                                .header("Authorization", studentToken)
                                .param("count", "5"))
                .andExpect(status().isForbidden());
    }

    // 題庫已接近上限時再出題，超過總量上限應被擋下
    @Test
    void testGenerateQuestions_QuotaExceeded() throws Exception {
        // 手動塞入 48 題
        for (int i = 0; i < 48; i++) {
            materialQuestionRepository.save(
                    MaterialQuestion.builder()
                            .material(testMaterial)
                            .type(QuestionType.SINGLE_CHOICE)
                            .question("測試題目 " + i)
                            .options(List.of("A", "B", "C", "D"))
                            .correctAnswer(List.of("A"))
                            .build());
        }

        // 教師再次嘗試生成 5 題，48 + 5 = 53 > 50，應被容量限制阻擋回傳 409 Conflict
        mockMvc.perform(
                        post("/v1/materials/" + testMaterial.getId() + "/questions/generate")
                                .header("Authorization", teacherToken)
                                .param("count", "5"))
                .andExpect(status().isConflict());
    }

    // 學生完整跑一輪測驗：抽題（不洩漏正確答案）➔ 提交 ➔ 擋重複提交 ➔ 查詢作答報告
    @Test
    void testStudentQuizLifecycle_Success() throws Exception {
        // 1. 先為教材建立 10 題可用考題
        for (int i = 0; i < 10; i++) {
            materialQuestionRepository.save(
                    MaterialQuestion.builder()
                            .material(testMaterial)
                            .type(QuestionType.SINGLE_CHOICE)
                            .question("這是第 " + i + " 題 Java 測驗？")
                            .options(List.of("A. 是", "B. 否", "C. 可能", "D. 不知"))
                            .correctAnswer(List.of("A"))
                            .explanation(new QuestionExplanation("解析", Map.of("A", "正確")))
                            .build());
        }

        // 2. 學生發起測驗抽題 (預期抽 10 題)
        String quizResponseBody =
                mockMvc.perform(
                                post("/v1/materials/" + testMaterial.getId() + "/quizzes")
                                        .header("Authorization", studentToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.score").isEmpty()) // 分數此時為 null
                        .andExpect(jsonPath("$.questions.length()").value(10))
                        // 🌟 重要安全檢驗：檢查回傳中是否被隱藏了 correctAnswer 與 explanation，防止學生看 JSON 作弊
                        .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist())
                        .andExpect(jsonPath("$.questions[0].explanation").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // 解析出測驗 ID 及各題作答 ID
        String quizIdStr = objectMapper.readTree(quizResponseBody).get("id").asText();
        UUID quizId = UUID.fromString(quizIdStr);

        Map<UUID, List<String>> answers = new HashMap<>();
        objectMapper
                .readTree(quizResponseBody)
                .get("questions")
                .forEach(
                        q -> {
                            UUID quizQuestionId = UUID.fromString(q.get("id").asText());
                            // 故意答對 8 題，答錯 2 題 (Java 第 0-7 題填 A [對]，其餘填 B [錯])
                            if (answers.size() < 8) {
                                answers.put(quizQuestionId, List.of("A"));
                            } else {
                                answers.put(quizQuestionId, List.of("B"));
                            }
                        });

        QuizSubmitRequest submitRequest = new QuizSubmitRequest(answers);

        // 3. 學生提交答案
        mockMvc.perform(
                        post("/v1/quizzes/" + quizId + "/submit")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(80)) // 對 8 題 = 80 分
                .andExpect(jsonPath("$.reviews.length()").value(10))
                .andExpect(jsonPath("$.reviews[0].userAnswer").isArray())
                .andExpect(jsonPath("$.reviews[0].explanation").exists()); // 提交後會顯示正確答案與解析供複習

        // 4. 重複提交應被阻擋 (409 Conflict)
        mockMvc.perform(
                        post("/v1/quizzes/" + quizId + "/submit")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isConflict());

        // 5. 學生查詢測驗明細報告
        mockMvc.perform(get("/v1/quizzes/" + quizId).header("Authorization", studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(80));
    }

    // 教師可查詢班級錯題率分析，學生則應被拒絕查詢
    @Test
    void testGetClassWrongQuestionAnalysis_Success() throws Exception {
        // 1. 建立 10 個考題以滿足題庫最少數量限制
        for (int i = 0; i < 10; i++) {
            materialQuestionRepository.save(
                    MaterialQuestion.builder()
                            .material(testMaterial)
                            .type(QuestionType.SINGLE_CHOICE)
                            .question("這是第 " + i + " 題 Java 測驗？")
                            .options(List.of("A. 是", "B. 否", "C. 可能", "D. 不知"))
                            .correctAnswer(List.of("A"))
                            .explanation(new QuestionExplanation("解析", Map.of("A", "正確")))
                            .build());
        }

        // 2. 學生開始測驗
        String quizResponseBody =
                mockMvc.perform(
                                post("/v1/materials/" + testMaterial.getId() + "/quizzes")
                                        .header("Authorization", studentToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String quizIdStr = objectMapper.readTree(quizResponseBody).get("id").asText();
        var questionsNode = objectMapper.readTree(quizResponseBody).get("questions");
        String materialQuestionIdStr = questionsNode.get(0).get("questionId").asText();

        // 學生提交答案 (第一個填 B 回答錯誤，其餘填 A 回答正確)
        Map<UUID, List<String>> answers = new HashMap<>();
        for (int i = 0; i < questionsNode.size(); i++) {
            String qqIdStr = questionsNode.get(i).get("id").asText();
            if (i == 0) {
                answers.put(UUID.fromString(qqIdStr), List.of("B"));
            } else {
                answers.put(UUID.fromString(qqIdStr), List.of("A"));
            }
        }

        QuizSubmitRequest submitRequest = new QuizSubmitRequest(answers);
        mockMvc.perform(
                        post("/v1/quizzes/" + quizIdStr + "/submit")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isOk());

        // 3. 教師查詢班級錯題分析
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/wrong-questions")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questionId").value(materialQuestionIdStr))
                .andExpect(jsonPath("$[0].totalAttempts").value(1))
                .andExpect(jsonPath("$[0].wrongAttempts").value(1))
                .andExpect(jsonPath("$[0].errorRate").value(1.0))
                .andExpect(jsonPath("$[0].optionDistribution.B").value(1));

        // 4. 學生查詢分析應被拒絕 (403 Forbidden)
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/wrong-questions")
                                .header("Authorization", studentToken))
                .andExpect(status().isForbidden());
    }

    // 教師觸發 AI 疑問分析可取得結果，學生查詢則應被拒絕
    @Test
    void testGetClassDoubtAnalysis_Success() throws Exception {
        ClassDoubtResponse mockResponse =
                new ClassDoubtResponse(
                        1,
                        List.of(
                                new DoubtTheme(
                                        "遞迴終止條件",
                                        "學生不知道如何停止遞迴",
                                        List.of("為什麼遞迴會StackOverflow？"),
                                        "建議上課時強調終止條件")));
        when(quizAiClient.analyzeDoubt(testMaterial.getId())).thenReturn(mockResponse);

        // 3. 教師查詢疑問分析 (需帶 regenerate=true 才會在無快取時觸發 AI 分析)
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken)
                                .param("regenerate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuestionsAnalyzed").value(1))
                .andExpect(jsonPath("$.themes[0].themeName").value("遞迴終止條件"))
                .andExpect(jsonPath("$.themes[0].description").value("學生不知道如何停止遞迴"));

        // 4. 學生查詢疑問分析應被拒絕 (403 Forbidden)
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", studentToken))
                .andExpect(status().isForbidden());
    }

    // AI 疑問分析的 Redis 快取、24 小時限流、併發鎖定機制是否如預期運作
    @Test
    void testGetClassDoubtAnalysis_RedisLockAndRateLimit() throws Exception {
        ClassDoubtResponse mockResponse =
                new ClassDoubtResponse(
                        1,
                        List.of(
                                new DoubtTheme(
                                        "JVM概念",
                                        "學生不懂JVM原理",
                                        List.of("為什麼需要 Java 虛擬機 JVM 呢？"),
                                        "多介紹JVM位元組碼的編譯流程")));
        when(quizAiClient.analyzeDoubt(testMaterial.getId())).thenReturn(mockResponse);

        // A. 第一次嘗試請求 (regenerate=false)：此時無快取，應回傳 404 Not Found (不自動觸發 AI)
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken))
                .andExpect(status().isNotFound());

        // B. 教師點擊「分析」(regenerate=true)：進行 AI 分析並寫入快取與限流 (200 OK)
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken)
                                .param("regenerate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].themeName").value("JVM概念"));

        // 驗證 Redis 中寫入了 cache 與 rate_limit
        org.junit.jupiter.api.Assertions.assertTrue(
                redisTemplate.hasKey("doubt_analysis:cache:" + testMaterial.getId()));
        org.junit.jupiter.api.Assertions.assertTrue(
                redisTemplate.hasKey("doubt_analysis:rate_limit:" + testMaterial.getId()));

        // B. 第二次請求 (regenerate=false)：直接讀取快取，不應再次呼叫 QuizAiClient
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].themeName").value("JVM概念"));

        // C. 第三次請求 (regenerate=true)：欲重新分析，但因為 24 小時限流未過，應返回 429 Too Many Requests
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken)
                                .param("regenerate", "true"))
                .andExpect(status().isTooManyRequests());

        // D. 驗證併發鎖定：手動在 Redis 中設定鎖，以 regenerate=true 請求應直接返回 409 Conflict
        redisTemplate.opsForValue().set("doubt_analysis:lock:" + testMaterial.getId(), "true");
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/analysis/doubts")
                                .header("Authorization", teacherToken)
                                .param("regenerate", "true"))
                .andExpect(status().isConflict());

        // 清理鎖
        redisTemplate.delete("doubt_analysis:lock:" + testMaterial.getId());
    }
}
