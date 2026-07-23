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

    public SseEmitter register(UUID jobId) {
        // 設定超時時間為 5 分鐘 (300,000ms)，保障 Gemini 平行出題時間充足
        SseEmitter emitter = new SseEmitter(300_000L);

        emitter.onCompletion(() -> cleanup(jobId));
        emitter.onTimeout(() -> cleanup(jobId));
        emitter.onError((ex) -> cleanup(jobId));

        emitters.put(jobId, emitter);

        try {
            emitter.send(
                    SseEmitter.event().name("INIT").data(Map.of("message", "SSE 連線成功，開始監聽任務狀態")));
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
                    cleanup(jobId);
                }
            } catch (IOException e) {
                log.error("發送任務狀態更新失敗: {}", jobId, e);
                cleanup(jobId);
            }
        }
    }

    private void cleanup(UUID jobId) {
        emitters.remove(jobId);
        log.debug("已清理並移除了任務的 SSE 連線: {}", jobId);
    }
}
