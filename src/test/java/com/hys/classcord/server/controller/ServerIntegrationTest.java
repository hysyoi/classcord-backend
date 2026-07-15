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

    @Test
    void testServerLifecycle() throws Exception {
        // 1. 建立伺服器
        CreateServerRequest createReq = new CreateServerRequest("Java Programming");
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

        UUID serverId = UUID.fromString(objectMapper.readTree(resJson).get("id").asText());

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

        // 2. 更改名稱 (學生改 ➔ 403 Forbidden)
        UpdateServerRequest updateReq = new UpdateServerRequest("New Name");
        mockMvc.perform(
                        put("/v1/servers/" + serverId)
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // 3. 加入伺服器 (學生加入 ➔ 成為 STUDENT)
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

        // 4. 退出伺服器 (學生退出 ➔ 成功)
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
