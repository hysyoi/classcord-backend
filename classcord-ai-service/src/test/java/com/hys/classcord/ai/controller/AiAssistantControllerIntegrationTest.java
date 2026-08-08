package com.hys.classcord.ai.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.ai.client.MaterialClient;
import com.hys.classcord.ai.dto.CreateSessionRequest;
import com.hys.classcord.ai.entity.AiMessage;
import com.hys.classcord.ai.entity.AiSession;
import com.hys.classcord.ai.enums.AiMessageRole;
import com.hys.classcord.ai.repository.AiMessageRepository;
import com.hys.classcord.ai.repository.AiSessionRepository;
import com.hys.classcord.ai.security.JwtUtils;
import com.hys.classcord.common.dto.InternalMaterialDto;
import com.hys.classcord.material.enums.MaterialStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 非 AI 端點的整合測試：session 建立/查詢/歷史訊息，不觸發真實 Gemini 呼叫
public class AiAssistantControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiSessionRepository aiSessionRepository;
    @Autowired private AiMessageRepository aiMessageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private MaterialClient materialClient;

    private UUID userId;
    private UUID otherUserId;
    private String userToken;
    private String otherUserToken;
    private UUID materialId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        userToken = "Bearer " + jwtUtils.generateToken(userId, "user@test.com");
        otherUserToken = "Bearer " + jwtUtils.generateToken(otherUserId, "other@test.com");
        materialId = UUID.randomUUID();
    }

    private InternalMaterialDto materialWithStatus(MaterialStatus status) {
        return InternalMaterialDto.builder().id(materialId).status(status).build();
    }

    // 注意：ai_sessions/ai_messages 在目前的測試資料庫上實際帶有指向 main-service users/materials 表的外鍵
    // （main-service 的 Flyway 一律比 ai-service 先跑，ai-service 自己那份無外鍵版本的 migration 只有在資料庫
    // 從未被 main-service migrate 過的情況下才會真的生效，目前的 CI/本機流程不會遇到那種情況）。
    // 因此這裡手動塞入滿足外鍵約束的最小 users/materials 資料，讓寫入 ai_sessions 不會被擋下來。
    private void seedUser(UUID id) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                id,
                "user-" + id,
                id + "@test.com");
    }

    private void seedMaterial(UUID id) {
        UUID ownerId = UUID.randomUUID();
        UUID serverId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                ownerId,
                "owner-" + ownerId,
                ownerId + "@test.com");
        jdbcTemplate.update(
                "INSERT INTO servers (id, name, owner_id) VALUES (?, ?, ?)",
                serverId,
                "server-" + serverId,
                ownerId);
        jdbcTemplate.update(
                "INSERT INTO channels (id, server_id, name) VALUES (?, ?, ?)",
                channelId,
                serverId,
                "channel-" + channelId);
        jdbcTemplate.update(
                "INSERT INTO messages (id, channel_id, user_id, content) VALUES (?, ?, ?, ?)",
                messageId,
                channelId,
                ownerId,
                "seed message");
        jdbcTemplate.update(
                "INSERT INTO materials (id, message_id, file_url, file_type, original_name) VALUES (?, ?, ?, ?, ?)",
                id,
                messageId,
                "https://mock/" + id,
                "pdf",
                "seed.pdf");
    }

    private AiSession seedSession(UUID owner) {
        return aiSessionRepository.save(
                AiSession.builder().userId(owner).materialId(materialId).build());
    }

    private void seedMessage(AiSession session, AiMessageRole role, String content) {
        aiMessageRepository.save(
                AiMessage.builder().session(session).role(role).content(content).build());
    }

    // 教材已啟用 AI 助教時，能成功建立新的對話會話
    @Test
    void testCreateSession_MaterialEnabled_Success() throws Exception {
        seedUser(userId);
        seedMaterial(materialId);
        when(materialClient.getMaterial(materialId))
                .thenReturn(materialWithStatus(MaterialStatus.ENABLED));

        mockMvc.perform(
                        post("/v1/materials/chat-sessions")
                                .header("Authorization", userToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateSessionRequest(materialId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialId").value(materialId.toString()));

        assertEquals(
                1,
                aiSessionRepository
                        .findByUserIdAndMaterialIdOrderByCreatedAtDesc(userId, materialId)
                        .size());
    }

    // 教材尚未完成 AI 助教啟用（例如仍在 PROCESSING）時，建立會話應被拒絕
    @Test
    void testCreateSession_MaterialNotEnabled_Rejected() throws Exception {
        when(materialClient.getMaterial(materialId))
                .thenReturn(materialWithStatus(MaterialStatus.PROCESSING));

        mockMvc.perform(
                        post("/v1/materials/chat-sessions")
                                .header("Authorization", userToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateSessionRequest(materialId))))
                .andExpect(status().isBadRequest());

        assertTrue(
                aiSessionRepository
                        .findByUserIdAndMaterialIdOrderByCreatedAtDesc(userId, materialId)
                        .isEmpty());
    }

    // 查詢會話列表只會回傳自己名下的會話，不會看到其他使用者的
    @Test
    void testListSessions_OnlyReturnsOwnSessions() throws Exception {
        seedUser(userId);
        seedUser(otherUserId);
        seedMaterial(materialId);
        seedSession(userId);
        seedSession(otherUserId);

        mockMvc.perform(
                        get("/v1/materials/chat-sessions")
                                .header("Authorization", userToken)
                                .param("materialId", materialId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // 會話歷史訊息應依時間正序（舊到新）回傳
    @Test
    void testGetSessionMessages_ReturnsChronologicalOrder() throws Exception {
        seedUser(userId);
        seedMaterial(materialId);
        AiSession session = seedSession(userId);
        seedMessage(session, AiMessageRole.USER, "第一個問題");
        seedMessage(session, AiMessageRole.ASSISTANT, "第一個回答");
        seedMessage(session, AiMessageRole.USER, "第二個問題");

        mockMvc.perform(
                        get("/v1/materials/chat-sessions/" + session.getId() + "/messages")
                                .header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].content").value("第一個問題"))
                .andExpect(jsonPath("$[1].content").value("第一個回答"))
                .andExpect(jsonPath("$[2].content").value("第二個問題"));
    }

    // 查詢不存在的會話應回傳 404
    @Test
    void testGetSessionMessages_SessionNotFound_Returns404() throws Exception {
        mockMvc.perform(
                        get("/v1/materials/chat-sessions/" + UUID.randomUUID() + "/messages")
                                .header("Authorization", userToken))
                .andExpect(status().isNotFound());
    }

    // 非會話擁有者查詢他人的對話歷史應被拒絕 (403)
    @Test
    void testGetSessionMessages_NotOwner_Returns403() throws Exception {
        seedUser(userId);
        seedMaterial(materialId);
        AiSession session = seedSession(userId);
        seedMessage(session, AiMessageRole.USER, "只有本人能看到的問題");

        mockMvc.perform(
                        get("/v1/materials/chat-sessions/" + session.getId() + "/messages")
                                .header("Authorization", otherUserToken))
                .andExpect(status().isForbidden());
    }

    // 全站當日尚無任何 AI 呼叫紀錄時，額度狀態應回傳歸零的數值
    @Test
    void testGetAiLimitStatus_NoUsageYet_ReturnsZero() throws Exception {
        mockMvc.perform(get("/v1/materials/ai-limit-status").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatCount").value(0))
                .andExpect(jsonPath("$.embeddingCount").value(0))
                .andExpect(jsonPath("$.estimatedCostUsd").value(0.0));
    }
}
