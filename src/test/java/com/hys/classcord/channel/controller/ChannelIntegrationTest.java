package com.hys.classcord.channel.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hys.classcord.BaseIntegrationTest;
import com.hys.classcord.auth.entity.User;
import com.hys.classcord.auth.repository.UserRepository;
import com.hys.classcord.auth.security.JwtUtils;
import com.hys.classcord.channel.dto.ChannelPositionDto;
import com.hys.classcord.channel.dto.CreateChannelRequest;
import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.channel.repository.ChannelRepository;
import com.hys.classcord.server.entity.Server;
import com.hys.classcord.server.entity.ServerMember;
import com.hys.classcord.server.enums.ServerRole;
import com.hys.classcord.server.repository.ServerMemberRepository;
import com.hys.classcord.server.repository.ServerRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// todo 有時間再寫單元測試
public class ChannelIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserRepository userRepository;
    @Autowired private ServerRepository serverRepository;
    @Autowired private ServerMemberRepository serverMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ObjectMapper objectMapper;

    private User teacher;
    private User student;
    private User outsider;
    private Server testServer;
    private String teacherToken;
    private String studentToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        // 1. 初始化使用者
        teacher =
                userRepository.save(
                        User.builder().username("Teacher").email("teacher@test.com").build());
        student =
                userRepository.save(
                        User.builder().username("Student").email("student@test.com").build());
        outsider =
                userRepository.save(
                        User.builder().username("Outsider").email("outsider@test.com").build());

        teacherToken = "Bearer " + jwtUtils.generateToken(teacher.getId(), teacher.getEmail());
        studentToken = "Bearer " + jwtUtils.generateToken(student.getId(), student.getEmail());
        outsiderToken = "Bearer " + jwtUtils.generateToken(outsider.getId(), outsider.getEmail());

        // 2. 直接在 DB 建立伺服器與成員關係，繞過 Server API 依賴
        testServer =
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
    }

    @Test
    void testChannelLifecycleAndPermissions() throws Exception {
        UUID serverId = testServer.getId();

        // 1. 建立第一個頻道
        CreateChannelRequest req1 = new CreateChannelRequest("General", ChannelType.GENERAL);
        String resJson1 =
                mockMvc.perform(
                                post("/v1/servers/" + serverId + "/channels")
                                        .header("Authorization", teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req1)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID channelId1 = UUID.fromString(objectMapper.readTree(resJson1).get("id").asText());

        // 學生建頻道 ➔ 失敗
        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/channels")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isForbidden());

        // 2. 建立第二個頻道 (確認 position 自增)
        CreateChannelRequest req2 = new CreateChannelRequest("Materials", ChannelType.MATERIAL);
        String resJson2 =
                mockMvc.perform(
                                post("/v1/servers/" + serverId + "/channels")
                                        .header("Authorization", teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req2)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID channelId2 = UUID.fromString(objectMapper.readTree(resJson2).get("id").asText());

        assertEquals(0, channelRepository.findById(channelId1).get().getPosition());
        assertEquals(1, channelRepository.findById(channelId2).get().getPosition());

        // 建立一個 ADMIN 頻道
        CreateChannelRequest adminReq =
                new CreateChannelRequest("Teacher Lounge", ChannelType.ADMIN);
        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/channels")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isCreated());

        // 3. 頻道隱私過濾：老師應看到 3 個，學生只應看到 2 個
        String resTeacher =
                mockMvc.perform(
                                get("/v1/servers/" + serverId + "/channels")
                                        .header("Authorization", teacherToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(3, objectMapper.readTree(resTeacher).size());

        String resStudent =
                mockMvc.perform(
                                get("/v1/servers/" + serverId + "/channels")
                                        .header("Authorization", studentToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(2, objectMapper.readTree(resStudent).size());

        // 4. 批量排序 (對調 position)
        List<ChannelPositionDto> reorderList =
                List.of(
                        new ChannelPositionDto(channelId1, 1),
                        new ChannelPositionDto(channelId2, 0));
        mockMvc.perform(
                        put("/v1/servers/" + serverId + "/channels/positions")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reorderList)))
                .andExpect(status().isNoContent());

        assertEquals(1, channelRepository.findById(channelId1).get().getPosition());
        assertEquals(0, channelRepository.findById(channelId2).get().getPosition());

        // 5. 局外人獲取列表 ➔ 應失敗被阻擋 (403 Forbidden)
        mockMvc.perform(
                        get("/v1/servers/" + serverId + "/channels")
                                .header("Authorization", outsiderToken))
                .andExpect(status().isForbidden());
    }
}
