package com.hys.classcord.message.controller;

import static org.junit.jupiter.api.Assertions.*;
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
import com.hys.classcord.message.dto.CreateMessageRequest;
import com.hys.classcord.message.dto.UpdateMessageRequest;
import com.hys.classcord.message.entity.Message;
import com.hys.classcord.message.repository.MessageRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MessageIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ServerMemberRepository serverMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ObjectMapper objectMapper;

    // 注入 JdbcTemplate 用於繞過 Hibernate 的軟刪除限制，直接驗證資料庫底層狀態
    @Autowired private JdbcTemplate jdbcTemplate;

    private User student;
    private Channel generalChannel;
    private Channel materialChannel;
    private Channel adminChannel;

    private String teacherToken;
    private String studentToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        truncateAll();
        // 1. 初始化使用者。teacher 與 outsider 只在 setUp 中用於建置環境，故設為局部變數
        User teacher =
                userRepository.save(
                        User.builder().username("Teacher").email("teacher@test.com").build());
        student =
                userRepository.save(
                        User.builder().username("Student").email("student@test.com").build());
        User outsider =
                userRepository.save(
                        User.builder().username("Outsider").email("outsider@test.com").build());

        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());
        studentToken = "Bearer " + jwtUtils.generateToken(student.getId(), student.getEmail());
        outsiderToken = "Bearer " + jwtUtils.generateToken(outsider.getId(), outsider.getEmail());

        // 2. 建立伺服器。testServer 亦可設為局部變數
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

        // 3. 建立各種類型的頻道
        generalChannel =
                channelRepository.save(
                        Channel.builder()
                                .server(testServer)
                                .name("一般討論")
                                .type(ChannelType.GENERAL)
                                .position(0)
                                .build());
        materialChannel =
                channelRepository.save(
                        Channel.builder()
                                .server(testServer)
                                .name("課程教材")
                                .type(ChannelType.MATERIAL)
                                .position(1)
                                .build());
        adminChannel =
                channelRepository.save(
                        Channel.builder()
                                .server(testServer)
                                .name("教師專區")
                                .type(ChannelType.ADMIN)
                                .position(2)
                                .build());
    }

    // 發送訊息並等待非同步落庫完成，回傳訊息 ID，供需要「訊息已確實存在」的測試重用
    private UUID postMessageAndAwaitPersisted(UUID channelId, String token, String content)
            throws Exception {
        String resJson =
                mockMvc.perform(
                                post("/v1/channels/" + channelId + "/messages")
                                        .header("Authorization", token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateMessageRequest(content))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID messageId = UUID.fromString(objectMapper.readTree(resJson).get("id").asText());
        assertNotNull(messageId);

        long limit = System.currentTimeMillis() + 8000;
        while (!messageRepository.existsById(messageId)) {
            if (System.currentTimeMillis() > limit) {
                throw new AssertionError("訊息在限時內未被非同步存儲: " + messageId);
            }
            Thread.sleep(50);
        }
        return messageId;
    }

    // 學生在一般頻道發送訊息 ➔ 應該成功
    @Test
    void testStudentPost_AllowedInGeneralChannel() throws Exception {
        UUID messageId =
                postMessageAndAwaitPersisted(generalChannel.getId(), studentToken, "Hello Class!");
        assertTrue(messageRepository.existsById(messageId));
    }

    // 學生在教材頻道、管理員頻道發送訊息 ➔ 應該都被拒絕
    @Test
    void testStudentPost_ForbiddenInMaterialAndAdminChannel() throws Exception {
        CreateMessageRequest req = new CreateMessageRequest("Hello Class!");

        mockMvc.perform(
                        post("/v1/channels/" + materialChannel.getId() + "/messages")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/v1/channels/" + adminChannel.getId() + "/messages")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // 老師在教材頻道、管理員頻道發送訊息 ➔ 應該都成功
    @Test
    void testTeacherPost_AllowedInMaterialAndAdminChannel() throws Exception {
        mockMvc.perform(
                        post("/v1/channels/" + materialChannel.getId() + "/messages")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateMessageRequest("PDF Material"))))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/v1/channels/" + adminChannel.getId() + "/messages")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateMessageRequest("Teacher talk"))))
                .andExpect(status().isCreated());
    }

    // 學生讀取一般頻道的歷史訊息 ➔ 應該成功
    @Test
    void testStudentRead_GeneralChannelMessages_Allowed() throws Exception {
        mockMvc.perform(
                        get("/v1/channels/" + generalChannel.getId() + "/messages")
                                .header("Authorization", studentToken))
                .andExpect(status().isOk());
    }

    // 學生讀取管理員頻道的歷史訊息 ➔ 應該被拒絕
    @Test
    void testStudentRead_AdminChannelMessages_Forbidden() throws Exception {
        mockMvc.perform(
                        get("/v1/channels/" + adminChannel.getId() + "/messages")
                                .header("Authorization", studentToken))
                .andExpect(status().isForbidden());
    }

    // 非班級成員的局外人讀取頻道訊息 ➔ 應該被拒絕
    @Test
    void testOutsiderRead_ChannelMessages_Forbidden() throws Exception {
        mockMvc.perform(
                        get("/v1/channels/" + generalChannel.getId() + "/messages")
                                .header("Authorization", outsiderToken))
                .andExpect(status().isForbidden());
    }

    // 學生可以編輯自己的訊息，但老師不能代為修改學生的發言內容
    @Test
    void testEditMessage_OwnerCanEditButOthersCannot() throws Exception {
        UUID messageId =
                postMessageAndAwaitPersisted(generalChannel.getId(), studentToken, "Hello Class!");

        // 學生編輯自己的訊息 ➔ 成功 (204 No Content)
        UpdateMessageRequest updateReq = new UpdateMessageRequest("Hello Class! (edited)");
        mockMvc.perform(
                        put("/v1/messages/" + messageId)
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isNoContent());

        Message updatedMessage =
                messageRepository
                        .findById(messageId)
                        .orElseThrow(() -> new AssertionError("找不到訊息"));
        assertEquals("Hello Class! (edited)", updatedMessage.getContent());

        // 老師編輯學生的訊息 ➔ 失敗 (403 Forbidden - 老師無權修改他人發言內容)
        mockMvc.perform(
                        put("/v1/messages/" + messageId)
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());
    }

    // 刪除訊息的權限與軟刪除行為：局外人不能刪、老師可代管理員刪學生訊息、學生可刪自己的訊息，
    // 且刪除後資料庫紀錄仍在（只是標記為已刪除），不是真的整筆消失
    @Test
    void testDeleteMessage_SoftDeleteAndPermissions() throws Exception {
        Message studentMsg =
                messageRepository.save(
                        Message.builder()
                                .channel(generalChannel)
                                .user(student)
                                .content("Temp message")
                                .build());

        // 局外人嘗試刪除 ➔ 失敗 (403 Forbidden)
        mockMvc.perform(
                        delete("/v1/messages/" + studentMsg.getId())
                                .header("Authorization", outsiderToken))
                .andExpect(status().isForbidden());

        // 老師刪除學生的訊息 (管理員刪除) ➔ 成功 (204 No Content)
        mockMvc.perform(
                        delete("/v1/messages/" + studentMsg.getId())
                                .header("Authorization", teacherToken))
                .andExpect(status().isNoContent());

        // 驗證 A: 透過 JPA 查詢，該訊息已被過濾，呈現「不存在」狀態
        assertFalse(messageRepository.existsById(studentMsg.getId()));

        // 驗證 B: 透過 Native SQL 驗證資料庫中確實存在該條記錄，且 deleted 被置為 true (軟刪除成功)
        Boolean studentMsgDeleted =
                jdbcTemplate.queryForObject(
                        "SELECT deleted FROM messages WHERE id = ?",
                        Boolean.class,
                        studentMsg.getId());
        assertTrue(studentMsgDeleted, "資料庫中的 deleted 欄位應被置為 true");

        // 學生刪除自己的原創訊息 ➔ 成功 (204 No Content)
        UUID ownMessageId =
                postMessageAndAwaitPersisted(
                        generalChannel.getId(), studentToken, "My own message");

        mockMvc.perform(
                        delete("/v1/messages/" + ownMessageId)
                                .header("Authorization", studentToken))
                .andExpect(status().isNoContent());

        // 驗證 A: JPA 查不到
        assertFalse(messageRepository.existsById(ownMessageId));

        // 驗證 B: 資料庫物理數據依然存在，deleted 被更新為 true
        Boolean originalMsgDeleted =
                jdbcTemplate.queryForObject(
                        "SELECT deleted FROM messages WHERE id = ?", Boolean.class, ownMessageId);
        assertTrue(originalMsgDeleted);
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    // 本測試類別停用了 @Transactional，訊息是非同步落庫，交易假回滾看不到背景 consumer thread 寫入的資料，
    // 只能用真的 TRUNCATE 清乾淨。有 CASCADE，任何外鍵參照到這裡列出的表的其他表（例如未來新增的表）
    // 也會被自動連帶清空，不需要每次新增表都手動加進這份清單。
    private void truncateAll() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE messages, channels, server_members, servers, users, materials, user_identities, material_questions, quizzes, quiz_questions, quiz_generation_jobs CASCADE");
    }
}
