package com.hys.classcord.quiz.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.ai.entity.MaterialChunk;
import com.hys.classcord.ai.repository.MaterialChunkRepository;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
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
    @Autowired private MaterialChunkRepository materialChunkRepository;
    @Autowired private MaterialQuestionRepository materialQuestionRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizQuestionRepository quizQuestionRepository;
    @Autowired private QuizService quizService;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ChatClient.Builder chatClientBuilder;

    private User teacher;
    private User student;
    private String teacherToken;
    private String studentToken;
    private Material testMaterial;

    @BeforeEach
    void setUp() throws Exception {
        // 清理資料庫以防干擾
        quizQuestionRepository.deleteAll();
        quizRepository.deleteAll();
        materialQuestionRepository.deleteAll();
        materialChunkRepository.deleteAll();

        // 1. 建立使用者與 Token
        teacher =
                userRepository.save(
                        User.builder().username("QuizTeacher").email("q-teacher@test.com").build());
        student =
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

        // 4. 初始化教材 Chunks 文本切片
        for (int i = 0; i < 15; i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("material_id", testMaterial.getId().toString());
            metadata.put("chunk_index", i);

            materialChunkRepository.save(
                    MaterialChunk.builder()
                            .content("這是有關於 Java 物件導向基礎的教材第 " + i + " 段內容。")
                            .metadata(metadata)
                            .build());
        }
    }

    @Test
    void testGenerateQuestions_Success() throws Exception {
        // Mock Spring AI ChatClient 串接，模擬 Gemini 回傳單題的結構化 JSON
        ChatClient mockChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(mockChatClient);
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(any(Consumer.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponseSpec);

        String mockAiJsonResponse =
                """
                {
                  "question": "Java 的 class 關鍵字用來做什麼？",
                  "options": [
                    "A. 宣告類別",
                    "B. 宣告介面",
                    "C. 宣告變數",
                    "D. 宣告常數"
                  ],
                  "correctAnswer": ["A"],
                  "explanation": {
                    "general": "本題測驗類別基本宣告。",
                    "options": {
                      "A": "正確，class 為類別宣告關鍵字。",
                      "B": "錯誤，介面應使用 interface。",
                      "C": "錯誤，宣告變數使用型別名稱。",
                      "D": "錯誤，常數宣告使用 final。"
                    }
                  }
                }
                """;
        when(mockCallResponseSpec.content()).thenReturn(mockAiJsonResponse);

        // 1. 教師發起出題 3 題 (預期異步返回 PENDING 狀態與 jobId)
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

        String jobIdStr = objectMapper.readTree(mvcResult).get("jobId").asText();
        UUID jobId = UUID.fromString(jobIdStr);

        // 2. 模擬背景處理 (直接同步呼叫以避免測試中非同步讀取未提交交易之干擾)
        quizService.executeQuizGenerationBackground(jobId, testMaterial.getId(), 3, "MEDIUM");

        // 3. 驗證題庫確實寫入 3 筆，且可供教師查詢
        mockMvc.perform(
                        get("/v1/materials/" + testMaterial.getId() + "/questions")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].question").value("Java 的 class 關鍵字用來做什麼？"))
                .andExpect(jsonPath("$[0].correctAnswer[0]").value("A"))
                .andExpect(jsonPath("$[0].explanation.options.B").value("錯誤，介面應使用 interface。"));

        // 驗證題庫確實寫入 3 筆
        long count =
                materialQuestionRepository.countByMaterialIdAndIsDeletedFalse(testMaterial.getId());
        assertEquals(3, count);
    }

    @Test
    void testGenerateQuestions_InsufficientPermission() throws Exception {
        // 學生嘗試發起出題，預期 403 Forbidden
        mockMvc.perform(
                        post("/v1/materials/" + testMaterial.getId() + "/questions/generate")
                                .header("Authorization", studentToken)
                                .param("count", "5"))
                .andExpect(status().isForbidden());
    }

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
}
