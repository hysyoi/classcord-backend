package com.hys.classcord.quiz.service;

import com.hys.classcord.quiz.dto.QuizJobStatusResponse;
import com.hys.classcord.quiz.enums.JobStatus;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SsePushManager {

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 註冊 SSE 連線。
     *
     * @param jobId 出題任務 ID
     * @param currentStatus 該任務目前在資料庫中的實際狀態（由呼叫端先查好傳入）， 用於支援「前端斷線重連（例如重新整理頁面）」：
     *     若任務早已完成/失敗，直接補送最終狀態並關閉連線，不讓前端傻等到 timeout； 若任務仍在進行中，註冊後也補送一次目前狀態快照，避免重連後畫面空白等不到事件。
     *     若呼叫端查不到這筆任務（jobId 無效），傳入 null 維持原本行為。
     */
    public SseEmitter register(UUID jobId, QuizJobStatusResponse currentStatus) {
        // 設定超時時間為 5 分鐘 (300,000ms)，保障 Gemini 平行出題時間充足
        SseEmitter emitter = new SseEmitter(300_000L);

        emitter.onCompletion(() -> cleanup(jobId, emitter));
        emitter.onTimeout(() -> cleanup(jobId, emitter));
        emitter.onError((ex) -> cleanup(jobId, emitter));

        boolean alreadyFinished =
                currentStatus != null
                        && (currentStatus.status() == JobStatus.COMPLETED
                                || currentStatus.status() == JobStatus.FAILED);

        if (alreadyFinished) {
            try {
                emitter.send(SseEmitter.event().name("STATUS_UPDATE").data(currentStatus));
                emitter.complete();
            } catch (IOException e) {
                log.error("重連時補送最終任務狀態失敗: {}", jobId, e);
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 若同一個 jobId 已有舊連線（例如前端重新整理、開了多個分頁），先讓舊連線正常結束，
        // 避免它被直接覆蓋、佔用連線資源直到 5 分鐘 timeout 才釋放
        SseEmitter previous = emitters.put(jobId, emitter);
        if (previous != null) {
            previous.complete();
        }

        try {
            emitter.send(
                    SseEmitter.event().name("INIT").data(Map.of("message", "SSE 連線成功，開始監聽任務狀態")));
            if (currentStatus != null) {
                emitter.send(SseEmitter.event().name("STATUS_UPDATE").data(currentStatus));
            }
        } catch (IOException e) {
            log.error("發送初始化 SSE 事件失敗: {}", jobId, e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    public void sendStatusUpdate(UUID jobId, QuizJobStatusResponse statusResponse) {
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("STATUS_UPDATE").data(statusResponse));

                if (statusResponse.status() == JobStatus.COMPLETED
                        || statusResponse.status() == JobStatus.FAILED) {
                    emitter.complete();
                    cleanup(jobId, emitter);
                }
            } catch (IOException e) {
                log.error("發送任務狀態更新失敗: {}", jobId, e);
                cleanup(jobId, emitter);
            }
        }
    }

    // 用「key + value」的條件式移除，確保只清掉自己對應的那個 emitter，
    // 不會誤刪同一個 jobId 底下已經被新連線覆蓋掉的 map 項目
    private void cleanup(UUID jobId, SseEmitter emitter) {
        emitters.remove(jobId, emitter);
        log.debug("已清理並移除了任務的 SSE 連線: {}", jobId);
    }
}
