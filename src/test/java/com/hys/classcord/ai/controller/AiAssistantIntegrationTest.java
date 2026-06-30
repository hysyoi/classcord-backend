package com.hys.classcord.ai.controller;

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
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.material.entity.Material;
import com.hys.classcord.material.enums.MaterialStatus;
import com.hys.classcord.material.repository.MaterialRepository;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
}
