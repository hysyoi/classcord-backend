package com.hys.classcord.ai.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.ai.dto.AiChatRequest;
import com.hys.classcord.ai.dto.CreateSessionRequest;
import com.hys.classcord.ai.entity.AiMessage;
import com.hys.classcord.ai.entity.AiSession;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.AiSessionRepository;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@DirtiesContext
public class AiAssistantIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private AiSessionRepository aiSessionRepository;
    @Autowired private AiMessageRepository aiMessageRepository;
    @SpyBean private RabbitTemplate rabbitTemplate;
    @Autowired private ObjectMapper objectMapper;

    // 模擬 AI 元件，避免測試時呼叫真實雲端 API 或因缺少 Key 而啟動失敗
    @MockBean private VectorStore vectorStore;
    @MockBean private ChatClient.Builder chatClientBuilder;

    private User teacher;
    private String teacherToken;
    private Material testMaterial;

    @BeforeEach
    void setUp() throws Exception {
        // 清空相關佇列以防干擾
        while (rabbitTemplate.receiveAndConvert(RabbitMQConfig.RAG_PROCESS_QUEUE) != null) {}

        // 1. 建立測試使用者與 Token
        teacher =
                userRepository.save(
                        User.builder()
                                .username("TestTeacher")
                                .email("teacher-ai@test.com")
                                .build());
        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());

        // 2. 建立伺服器、頻道與訊息
        Server server =
                serverRepository.save(Server.builder().name("AI Class").owner(teacher).build());
        Channel channel =
                channelRepository.save(
                        Channel.builder()
                                .server(server)
                                .name("教材區")
                                .type(ChannelType.MATERIAL)
                                .build());
        Message message =
                messageRepository.save(
                        Message.builder().channel(channel).user(teacher).content("課程大綱").build());

        // 3. 建立測試教材 (預設為 DISABLED)
        testMaterial =
                materialRepository.save(
                        Material.builder()
                                .message(message)
                                .fileUrl("https://b2.com/syllabus.pdf")
                                .fileType("pdf")
                                .originalName("syllabus.pdf")
                                .fileSize(1024L)
                                .status(MaterialStatus.DISABLED)
                                .build());
    }

    @Test
    void testEnableAiAssistantLifecycle() throws Exception {
        UUID materialId = testMaterial.getId();

        // ==========================================
        // 1. 首次點擊啟用 AI ➔ 成功 (200 OK)
        // ==========================================
        mockMvc.perform(
                        post("/v1/materials/" + materialId + "/enable-ai")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("AI 助教啟用請求已成功送出處理中"));

        // 驗證 A: 資料庫狀態已更新為 PROCESSING
        Material materialInDb = materialRepository.findById(materialId).orElseThrow();
        assertEquals(MaterialStatus.PROCESSING, materialInDb.getStatus());

        // 驗證 B: 透過 Mockito Spy 驗證 RabbitTemplate 是否確實發送了正確的消息
        verify(rabbitTemplate, times(1))
                .convertAndSend(
                        eq(RabbitMQConfig.AI_EXCHANGE),
                        eq(RabbitMQConfig.ROUTING_KEY_RAG_PROCESS),
                        eq(materialId.toString()));

        // ==========================================
        // 2. 重複點擊啟用 (PROCESSING) ➔ 失敗 (409 Conflict)
        // ==========================================
        mockMvc.perform(
                        post("/v1/materials/" + materialId + "/enable-ai")
                                .header("Authorization", teacherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_010")); // 驗證自訂錯誤碼

        // ==========================================
        // 3. 已啟用狀態 (ENABLED) 重複點擊 ➔ 失敗 (400 Bad Request)
        // ==========================================
        // 手動模擬背景 RAG 完成更新狀態為 ENABLED
        testMaterial.markAsEnabled();
        materialRepository.save(testMaterial);

        mockMvc.perform(
                        post("/v1/materials/" + materialId + "/enable-ai")
                                .header("Authorization", teacherToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATERIAL_011"));
    }

    @Test
    void testSessionBasedChatLifecycle() throws Exception {
        // ==========================================
        // 1. 在教材未啟用時，嘗試建立會話 ➔ 失敗 (409 Conflict / AI_ASSISTANT_PROCESSING)
        // ==========================================
        CreateSessionRequest createRequest = new CreateSessionRequest(testMaterial.getId());
        mockMvc.perform(
                        post("/v1/materials/chat-sessions")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isConflict());

        // ==========================================
        // 2. 模擬教材啟用成功，建立會話 ➔ 成功 (200 OK)
        // ==========================================
        testMaterial.markAsEnabled();
        materialRepository.save(testMaterial);

        mockMvc.perform(
                        post("/v1/materials/chat-sessions")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.materialId").value(testMaterial.getId().toString()));

        // 從資料庫找出會話
        List<AiSession> sessions =
                aiSessionRepository.findByUserIdAndMaterialIdOrderByCreatedAtDesc(
                        teacher.getId(), testMaterial.getId());
        assertFalse(sessions.isEmpty());
        UUID sessionId = sessions.get(0).getId();

        // ==========================================
        // 3. 查詢會話列表 ➔ 成功 (200 OK)
        // ==========================================
        mockMvc.perform(
                        get("/v1/materials/chat-sessions")
                                .header("Authorization", teacherToken)
                                .param("materialId", testMaterial.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId.toString()));

        // ==========================================
        // 4. 模擬連續對話 (Spring AI ChatClient 串接) ➔ 成功 (200 OK)
        // ==========================================
        ChatClient mockChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec mockRequestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec mockCallResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(mockChatClient);
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.messages(anyList())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(any(Consumer.class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.advisors(any(Advisor[].class))).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponseSpec);
        when(mockCallResponseSpec.content()).thenReturn("這是模擬的 AI 助教答覆");

        AiChatRequest chatRequest = new AiChatRequest("哈囉，我想問這份講義在講什麼？");
        mockMvc.perform(
                        post("/v1/materials/chat-sessions/" + sessionId + "/chat")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("這是模擬的 AI 助教答覆"));

        // ==========================================
        // 5. 獲取會話歷史訊息，確認兩筆訊息已入庫（學生 user 提問 + 助教 assistant 回答）
        // ==========================================
        mockMvc.perform(
                        get("/v1/materials/chat-sessions/" + sessionId + "/messages")
                                .header("Authorization", teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("哈囉，我想問這份講義在講什麼？"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("這是模擬的 AI 助教答覆"));

        // 直接查 Repository 確保落庫成功
        List<AiMessage> dbMessages =
                aiMessageRepository.findBySessionIdOrderByCreatedAtDesc(
                        sessionId, org.springframework.data.domain.Limit.of(10));
        assertEquals(2, dbMessages.size());
    }
}
