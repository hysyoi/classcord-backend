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

    // 以老師身分建立頻道，回傳頻道 ID，供需要「已存在頻道」的測試重用
    private UUID createChannel(String name, ChannelType type) throws Exception {
        CreateChannelRequest req = new CreateChannelRequest(name, type);
        String resJson =
                mockMvc.perform(
                                post("/v1/servers/" + testServer.getId() + "/channels")
                                        .header("Authorization", teacherToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(objectMapper.readTree(resJson).get("id").asText());
    }

    // 只有老師能建立頻道，學生嘗試建立應被拒絕
    @Test
    void testCreateChannel_TeacherOnly() throws Exception {
        createChannel("General", ChannelType.GENERAL);

        // 學生建頻道 ➔ 失敗
        CreateChannelRequest req = new CreateChannelRequest("General", ChannelType.GENERAL);
        mockMvc.perform(
                        post("/v1/servers/" + testServer.getId() + "/channels")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // 依序建立多個頻道時，position 應該自動遞增
    @Test
    void testCreateChannel_PositionAutoIncrements() throws Exception {
        UUID channelId1 = createChannel("General", ChannelType.GENERAL);
        UUID channelId2 = createChannel("Materials", ChannelType.MATERIAL);

        assertEquals(0, channelRepository.findById(channelId1).get().getPosition());
        assertEquals(1, channelRepository.findById(channelId2).get().getPosition());
    }

    // 老師看得到全部頻道（含管理員頻道），學生的頻道列表應該把管理員頻道過濾掉
    @Test
    void testListChannels_FiltersAdminChannelForStudents() throws Exception {
        createChannel("General", ChannelType.GENERAL);
        createChannel("Materials", ChannelType.MATERIAL);

        // 建立一個 ADMIN 頻道 (藉由 repository 直接寫入以模擬預設的管理頻道，因為 API 已阻擋手動新增 ADMIN 頻道)
        channelRepository.save(
                com.hys.classcord.channel.entity.Channel.builder()
                        .name("Teacher Lounge")
                        .server(testServer)
                        .type(ChannelType.ADMIN)
                        .position(2)
                        .build());

        // 老師應看到 3 個，學生只應看到 2 個 (ADMIN 頻道被過濾)
        String resTeacher =
                mockMvc.perform(
                                get("/v1/servers/" + testServer.getId() + "/channels")
                                        .header("Authorization", teacherToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(3, objectMapper.readTree(resTeacher).size());

        String resStudent =
                mockMvc.perform(
                                get("/v1/servers/" + testServer.getId() + "/channels")
                                        .header("Authorization", studentToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(2, objectMapper.readTree(resStudent).size());
    }

    // 非班級成員的局外人查詢頻道列表 ➔ 應該被拒絕
    @Test
    void testListChannels_OutsiderForbidden() throws Exception {
        createChannel("General", ChannelType.GENERAL);

        mockMvc.perform(
                        get("/v1/servers/" + testServer.getId() + "/channels")
                                .header("Authorization", outsiderToken))
                .andExpect(status().isForbidden());
    }

    // 批量調整頻道順序後，各頻道的 position 應該正確更新
    @Test
    void testReorderChannels_UpdatesPositions() throws Exception {
        UUID channelId1 = createChannel("General", ChannelType.GENERAL);
        UUID channelId2 = createChannel("Materials", ChannelType.MATERIAL);

        // 批量排序 (對調 position)
        List<ChannelPositionDto> reorderList =
                List.of(
                        new ChannelPositionDto(channelId1, 1),
                        new ChannelPositionDto(channelId2, 0));
        mockMvc.perform(
                        put("/v1/servers/" + testServer.getId() + "/channels/positions")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reorderList)))
                .andExpect(status().isNoContent());

        assertEquals(1, channelRepository.findById(channelId1).get().getPosition());
        assertEquals(0, channelRepository.findById(channelId2).get().getPosition());
    }

    // 管理員頻道只能由系統自動建立，任何人透過 API 手動建立都應該被拒絕
    @Test
    void testCannotCreateAdminChannel() throws Exception {
        UUID serverId = testServer.getId();
        CreateChannelRequest adminReq =
                new CreateChannelRequest("Another Admin", ChannelType.ADMIN);
        mockMvc.perform(
                        post("/v1/servers/" + serverId + "/channels")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isBadRequest());
    }
}
