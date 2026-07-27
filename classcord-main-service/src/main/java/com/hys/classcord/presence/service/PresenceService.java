package com.hys.classcord.presence.service;

import com.hys.classcord.presence.dto.PresenceEvent;
import com.hys.classcord.server.repository.ServerMemberRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis 追蹤使用者的在線狀態。 同一使用者可能同時開啟多個分頁/裝置連線，因此以連線計數器判斷「真正離線」的時機， 而非單純依靠單一 WebSocket Session
 * 的連線/斷線事件。
 *
 * <p>刻意不使用一個全站共用的「在線名單」集合：如果用共用集合，只要系統中還有任何一個人在線， 定時續期就會一直幫整個集合續命，導致某個人非正常斷線（沒觸發到 disconnect 事件）時，
 * 他的殘留紀錄會被「別人的在線狀態」一路續下去，變成永遠除不掉的幽靈在線。 改成每個使用者的在線狀態只綁定在自己專屬的 presence:conn:{userId} 這把 key
 * 上，是否在線直接看這把 key 存不存在（EXISTS）， TTL 到期就自動、獨立地消失，不會被其他人拖累。
 *
 * <p>廣播範圍比照聊天訊息，只送到該使用者所屬的各個伺服器頻道（/topic/servers/{serverId}/presence）， 不對全站廣播。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String CONN_COUNT_KEY_PREFIX = "presence:conn:";

    // TTL 設定為定時續期週期的 3 倍，確保正常運作下不會因為排程稍微延遲就被誤判過期
    private static final Duration TTL = Duration.ofSeconds(45);
    private static final long RENEW_INTERVAL_MS = 15_000;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry simpUserRegistry;
    private final ServerMemberRepository serverMemberRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    // 把「計數 + 條件式刪除」包成 Lua script 原子執行，避免 DECR 到 0 跟刪除 key 這兩步中間，
    // 被一個緊接著到來的新連線插隊（該連線的 INCR 會被夾在中間，若沒包成原子操作，可能被誤刪）
    private final RedisScript<Long> presenceConnectScript;
    private final RedisScript<Long> presenceDisconnectScript;

    /**
     * 服務啟動時清空所有殘留的在線狀態。 因為目前是單一 instance 部署，服務重啟就代表所有舊的 WebSocket 連線必定都已經斷開， 藉此清除因行程被強制關閉（kill /
     * crash）而沒有正常觸發斷線事件、殘留在 Redis 裡的幽靈在線紀錄。 若之後擴展為多 instance 部署，這裡就不能再無條件清空，需要改用其他方式（例如 TTL）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void clearStaleStateOnStartup() {
        Set<String> connKeys = scanKeys(CONN_COUNT_KEY_PREFIX + "*");
        if (!connKeys.isEmpty()) {
            redisTemplate.delete(connKeys);
        }
        log.info("已清空殘留的在線狀態資料（服務重啟）");
    }

    /**
     * 用 SCAN 取代 KEYS 尋找殘留的 key。這顆 Redis 跟其他服務共用（JWT 黑名單、向量資料庫等）， KEYS 指令會依整個 keyspace
     * 大小阻塞單一執行緒，SCAN 則是分批、非阻塞地游標掃描。
     */
    private Set<String> scanKeys(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        Set<String> result =
                redisTemplate.execute(
                        (RedisConnection connection) -> {
                            Set<String> keys = new HashSet<>();
                            try (Cursor<byte[]> cursor = connection.scan(options)) {
                                while (cursor.hasNext()) {
                                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                                }
                            }
                            return keys;
                        });
        return result != null ? result : Set.of();
    }

    /** 使用者建立一條新的 WebSocket 連線，連線數由 0 變 1 時才視為剛上線並廣播 */
    public void handleConnect(String userId) {
        String connKey = CONN_COUNT_KEY_PREFIX + userId;
        Long count =
                redisTemplate.execute(
                        presenceConnectScript, List.of(connKey), String.valueOf(TTL.getSeconds()));
        if (count != null && count == 1) {
            applicationEventPublisher.publishEvent(
                    new PresenceEvent(UUID.fromString(userId), true));
            log.info("使用者上線: {}", userId);
        }
    }

    /** 使用者的其中一條 WebSocket 連線斷開，連線數歸零時才視為真正離線並廣播 */
    public void handleDisconnect(String userId) {
        String connKey = CONN_COUNT_KEY_PREFIX + userId;
        Long count =
                redisTemplate.execute(
                        presenceDisconnectScript,
                        List.of(connKey),
                        String.valueOf(TTL.getSeconds()));
        if (count == null || count <= 0) {
            applicationEventPublisher.publishEvent(
                    new PresenceEvent(UUID.fromString(userId), false));
            log.info("使用者離線: {}", userId);
        }
    }

    /**
     * 定時為目前真正連線中的使用者續期 TTL，資料來源是 Spring 內建、即時反映連線狀態的 SimpUserRegistry，
     * 不依賴我們自己的計數器（避免雙重誤差）。行程存活期間持續續期，一旦行程被強制關閉， 沒有人續期，殘留的 Redis key 最慢會在 TTL 內自動過期。
     */
    @Scheduled(fixedRate = RENEW_INTERVAL_MS)
    public void renewTtlForConnectedUsers() {
        Set<SimpUser> users = simpUserRegistry.getUsers();
        if (users.isEmpty()) {
            return;
        }
        // 用 pipeline 把所有人的 EXPIRE 指令一次送出，避免連線人數一多，
        // 逐一呼叫造成大量的 Redis 網路來回（RTT）
        redisTemplate.executePipelined(
                (RedisCallback<Object>)
                        connection -> {
                            for (SimpUser user : users) {
                                connection
                                        .keyCommands()
                                        .expire(
                                                toBytes(CONN_COUNT_KEY_PREFIX + user.getName()),
                                                TTL.getSeconds());
                            }
                            return null;
                        });
    }

    /** 是否在線直接看這個使用者的連線計數器 key 存不存在，不需要另外維護一份在線名單 */
    public boolean isOnline(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CONN_COUNT_KEY_PREFIX + userId));
    }

    /** 批次查詢指定的一批使用者是否在線，用 pipeline 一次送出多個 EXISTS， 只查詢呼叫端關心的那幾個人，而不是撈一份全站在線名單回來比對。 */
    public Set<String> filterOnlineUserIds(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<String> idList = List.copyOf(userIds);
        List<Object> results =
                redisTemplate.executePipelined(
                        (RedisCallback<Object>)
                                connection -> {
                                    for (String id : idList) {
                                        connection
                                                .keyCommands()
                                                .exists(toBytes(CONN_COUNT_KEY_PREFIX + id));
                                    }
                                    return null;
                                });
        Set<String> online = new HashSet<>();
        for (int i = 0; i < idList.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                online.add(idList.get(i));
            }
        }
        return online;
    }

    private static byte[] toBytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 實際查詢資料庫並廣播的地方，故意做成非同步（@Async）的事件監聽器，而不是直接在 handleConnect/handleDisconnect 裡同步呼叫：後者是被
     * WebSocket 連線/斷線事件同步觸發的， 若在同一條執行緒上等 DB 查詢（findServerIdsByUserId）跟多次 WebSocket 廣播做完，
     * 遇到大量使用者同時重連（例如網路波動）時容易讓執行緒池與 DB 連線池被佔滿、拖慢整體連線處理。 改用 ApplicationEventPublisher 發佈事件，讓
     * handleConnect/handleDisconnect 發完事件就能立刻返回。
     *
     * <p>注意：@Async 只有透過 Spring 的代理呼叫才會生效，同一個類別內部直接呼叫方法（this.xxx()）會繞過代理、
     * 不會真的非同步，因此這裡刻意透過事件機制觸發，而不是讓 handleConnect 直接呼叫這個方法。
     */
    @Async
    @EventListener
    public void onPresenceChanged(PresenceEvent event) {
        List<UUID> serverIds = serverMemberRepository.findServerIdsByUserId(event.userId());
        for (UUID serverId : serverIds) {
            messagingTemplate.convertAndSend("/topic/servers/" + serverId + "/presence", event);
        }
    }
}
