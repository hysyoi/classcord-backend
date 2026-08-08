package com.hys.classcord.presence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hys.classcord.presence.dto.PresenceEvent;
import com.hys.classcord.server.repository.ServerMemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;

// 純單元測試：不啟動 Spring context、不連真的 Redis，全部依賴用 Mockito 替身取代，
// 只驗證 PresenceService 本身的判斷邏輯。
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SimpUserRegistry simpUserRegistry;
    @Mock private ServerMemberRepository serverMemberRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private RedisScript<Long> presenceConnectScript;
    @Mock private RedisScript<Long> presenceDisconnectScript;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PresenceService presenceService;

    @BeforeEach
    void setUp() {
        presenceService =
                new PresenceService(
                        redisTemplate,
                        messagingTemplate,
                        simpUserRegistry,
                        serverMemberRepository,
                        applicationEventPublisher,
                        meterRegistry,
                        presenceConnectScript,
                        presenceDisconnectScript);
    }

    // 連線數從 0 變 1（Lua script 回傳 1）才代表剛上線，應該廣播上線事件
    @Test
    void handleConnect_publishesOnlineEvent_whenConnectionCountBecomesOne() {
        String userId = UUID.randomUUID().toString();
        when(redisTemplate.execute(eq(presenceConnectScript), anyList(), any())).thenReturn(1L);

        presenceService.handleConnect(userId);

        ArgumentCaptor<PresenceEvent> captor = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(UUID.fromString(userId));
        assertThat(captor.getValue().online()).isTrue();
    }

    // 連線數變成 2（同一使用者多開一個分頁）不算新上線，不應該重複廣播
    @Test
    void handleConnect_doesNotPublish_whenConnectionCountIsAlreadyMoreThanOne() {
        String userId = UUID.randomUUID().toString();
        when(redisTemplate.execute(eq(presenceConnectScript), anyList(), any())).thenReturn(2L);

        presenceService.handleConnect(userId);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // 連線數歸零（Lua script 回傳 0）代表真正離線，應該廣播離線事件
    @Test
    void handleDisconnect_publishesOfflineEvent_whenConnectionCountReachesZero() {
        String userId = UUID.randomUUID().toString();
        when(redisTemplate.execute(eq(presenceDisconnectScript), anyList(), any())).thenReturn(0L);

        presenceService.handleDisconnect(userId);

        ArgumentCaptor<PresenceEvent> captor = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(UUID.fromString(userId));
        assertThat(captor.getValue().online()).isFalse();
    }

    // key 已經因為 TTL 過期被自動刪除（Lua script 回傳 null）也視為離線，一樣要廣播
    @Test
    void handleDisconnect_publishesOfflineEvent_whenScriptReturnsNull() {
        String userId = UUID.randomUUID().toString();
        when(redisTemplate.execute(eq(presenceDisconnectScript), anyList(), any()))
                .thenReturn(null);

        presenceService.handleDisconnect(userId);

        // 用明確型別的 any(PresenceEvent.class)，避免 ApplicationEventPublisher 的
        // publishEvent(ApplicationEvent) / publishEvent(Object) 兩個多載讓編譯器選錯對象
        verify(applicationEventPublisher).publishEvent(any(PresenceEvent.class));
    }

    // 使用者還有其他分頁連線中（count 仍大於 0）不算真正離線，不應該廣播
    @Test
    void handleDisconnect_doesNotPublish_whenOtherConnectionsStillActive() {
        String userId = UUID.randomUUID().toString();
        when(redisTemplate.execute(eq(presenceDisconnectScript), anyList(), any())).thenReturn(1L);

        presenceService.handleDisconnect(userId);

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // 核心防呆邏輯：事件本身攜帶的 online 快照值可能已經過時（非同步處理順序不保證），
    // 廣播當下必須重新查詢 Redis 現況而不是直接信任事件裡的舊值。
    // 廣播對象則是這位使用者所屬的每一個伺服器頻道。
    @Test
    void onPresenceChanged_broadcastsToEveryServerTheUserBelongsTo() {
        UUID userId = UUID.randomUUID();
        UUID serverId1 = UUID.randomUUID();
        UUID serverId2 = UUID.randomUUID();
        PresenceEvent event = new PresenceEvent(userId, false);

        when(redisTemplate.hasKey("presence:conn:" + userId)).thenReturn(true);
        when(serverMemberRepository.findServerIdsByUserId(userId))
                .thenReturn(List.of(serverId1, serverId2));

        presenceService.onPresenceChanged(event);

        ArgumentCaptor<PresenceEvent> payloadCaptor = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(messagingTemplate)
                .convertAndSend(
                        eq("/topic/servers/" + serverId1 + "/presence"), payloadCaptor.capture());
        verify(messagingTemplate)
                .convertAndSend(
                        eq("/topic/servers/" + serverId2 + "/presence"), any(PresenceEvent.class));
        // 兩次廣播的內容都應該是重新查詢到的「在線」狀態，而不是事件原本攜帶的 false
        assertThat(payloadCaptor.getValue().online()).isTrue();
        assertThat(payloadCaptor.getValue().userId()).isEqualTo(userId);
    }

    // 空的 id 集合應該提早回傳，不需要真的呼叫 Redis pipeline
    @Test
    void filterOnlineUserIds_returnsEmptySet_withoutCallingRedis_whenInputIsEmpty() {
        Set<String> result = presenceService.filterOnlineUserIds(List.of());

        assertThat(result).isEmpty();
        verify(redisTemplate, never())
                .executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
    }

    // pipeline 回傳的布林結果要跟原始輸入的 id 順序一一對應，只留下 true 的那幾個
    @Test
    void filterOnlineUserIds_returnsOnlyIdsWhoseExistsResultIsTrue() {
        List<String> ids = List.of("user-1", "user-2", "user-3");
        when(redisTemplate.executePipelined(
                        any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(List.of(true, false, true));

        Set<String> result = presenceService.filterOnlineUserIds(ids);

        assertThat(result).containsExactlyInAnyOrder("user-1", "user-3");
    }
}
