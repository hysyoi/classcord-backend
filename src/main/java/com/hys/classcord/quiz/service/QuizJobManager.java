package com.hys.classcord.quiz.service;

import com.hys.classcord.quiz.dto.QuizJobStatusResponse;
import com.hys.classcord.quiz.entity.QuizGenerationJob;
import com.hys.classcord.quiz.enums.JobStatus;
import com.hys.classcord.quiz.repository.QuizGenerationJobRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class QuizJobManager {

    private final QuizGenerationJobRepository quizGenerationJobRepository;
    private final SsePushManager ssePushManager;

    /**
     * 在獨立的新交易 (Propagation.REQUIRES_NEW) 中更新 Job 狀態，並同步推送 SSE 進度事件。 這能保障即使主出題交易因為異常回滾，Job 的 FAILED
     * 狀態與錯誤訊息依然能被寫入資料庫並推送給前端。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateJobStatusAndPushRequiresNew(
            UUID jobId, UUID materialId, JobStatus status, String errorMessage) {
        QuizGenerationJob job = quizGenerationJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            if (status == JobStatus.FAILED) {
                job.markAsFailed(errorMessage);
            } else {
                job.updateStatus(status);
            }
            // 立刻寫入資料庫，保障異步查詢與 SSE 通知之一致性
            quizGenerationJobRepository.saveAndFlush(job);
        }

        // 發送 SSE 即時進度更新給訂閱的用戶端
        ssePushManager.sendStatusUpdate(
                jobId, new QuizJobStatusResponse(jobId, materialId, status, errorMessage));
    }
}
