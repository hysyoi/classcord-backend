package com.hys.classcord.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.server.dto.CreateServerRequest;
import com.hys.classcord.server.dto.UpdateServerRequest;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public class ServerIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ServerMemberRepository serverMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ObjectMapper objectMapper;

    private User teacher;
    private User student;
    private String teacherToken;
    private String studentToken;

    @BeforeEach
    void setUp() {
        teacher =
                userRepository.save(
                        User.builder()
                                .username("TeacherUser")
                                .email("teacher@classcord.com")
                                .build());
        student =
                userRepository.save(
                        User.builder()
                                .username("StudentUser")
                                .email("student@classcord.com")
                                .build());
        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());
        studentToken = "Bearer " + jwtUtils.generateToken(student.getId(), student.getEmail());
    }

    // 以老師身分建立一個伺服器，回傳伺服器 ID，供需要「已存在伺服器」的測試重用
    private UUID createServer(String name) throws Exception {
        CreateServerRequest createReq = new CreateServerRequest(name);
        String resJson =
                mockMvc.perform(
                                post("/v1/servers")
                                        .header("Authorization", teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(objectMapper.readTree(resJson).get("id").asText());
    }

    // 建立伺服器後，建立者應自動成為 TEACHER，並自動產生預設的三個頻道
    @Test
    void testCreateServer_SetsCreatorAsTeacherAndCreatesDefaultChannels() throws Exception {
        UUID serverId = createServer("Java Programming");

        // 驗證建立者為 TEACHER 角色
        ServerMember ownerMember =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, teacher.getId())
                        .orElse(null);
        assertNotNull(ownerMember);
        assertEquals(ServerRole.TEACHER, ownerMember.getRole());

        // 驗證是否自動建立了預設頻道 (討論頻道 + 教材頻道 + 管理頻道)
        java.util.List<com.hys.classcord.channel.entity.Channel> channels =
                channelRepository.findByServerIdOrderByPositionAsc(serverId);
        assertEquals(3, channels.size());
        assertEquals("討論頻道", channels.get(0).getName());
        assertEquals(
                com.hys.classcord.channel.enums.ChannelType.GENERAL, channels.get(0).getType());
        assertEquals("教材頻道", channels.get(1).getName());
        assertEquals(
                com.hys.classcord.channel.enums.ChannelType.MATERIAL, channels.get(1).getType());
        assertEquals("管理頻道", channels.get(2).getName());
        assertEquals(com.hys.classcord.channel.enums.ChannelType.ADMIN, channels.get(2).getType());
    }

    // 只有老師能修改伺服器名稱，學生嘗試修改應被拒絕
    @Test
    void testUpdateServer_OnlyTeacherAllowed() throws Exception {
        UUID serverId = createServer("Java Programming");

        // 更改名稱 (學生改 ➔ 403 Forbidden)
        UpdateServerRequest updateReq = new UpdateServerRequest("New Name");
        mockMvc.perform(
                        put("/v1/servers/" + serverId)
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());
    }

    // 加入伺服器後應自動被賦予 STUDENT 角色
    @Test
    void testJoinServer_AssignsStudentRole() throws Exception {
        UUID serverId = createServer("Java Programming");

        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/join")
                                .header("Authorization", studentToken))
                .andExpect(status().isCreated());

        ServerMember studentMember =
                serverMemberRepository
                        .findByServerIdAndUserId(serverId, student.getId())
                        .orElse(null);
        assertNotNull(studentMember);
        assertEquals(ServerRole.STUDENT, studentMember.getRole());
    }

    // 學生可以自由退出伺服器，但擁有者（老師）不能退出自己建立的伺服器
    @Test
    void testLeaveServer_StudentCanLeaveButOwnerCannot() throws Exception {
        UUID serverId = createServer("Java Programming");

        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/join")
                                .header("Authorization", studentToken))
                .andExpect(status().isCreated());

        // 學生退出 ➔ 成功
        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/leave")
                                .header("Authorization", studentToken))
                .andExpect(status().isNoContent());

        // 老師退出 ➔ 失敗 (擁有者不能退出)
        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/leave")
                                .header("Authorization", teacherToken))
                .andExpect(status().isBadRequest());
    }

    // 每位老師最多只能建立 10 個伺服器，第 11 個應該被擋下
    @Test
    void testCreateServerLimit() throws Exception {
        // 建立 10 個伺服器
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(
                            post("/v1/servers")
                                    .header("Authorization", teacherToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    new CreateServerRequest("Class-" + i))))
                    .andExpect(status().isCreated());
        }

        // 第 11 個 ➔ 應失敗
        mockMvc.perform(
                        post("/v1/servers")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new CreateServerRequest("Class-Too-Many"))))
                .andExpect(status().isBadRequest());
    }
}
