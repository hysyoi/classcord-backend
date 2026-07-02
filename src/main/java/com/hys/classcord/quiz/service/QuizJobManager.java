package com.hys.classcord.quiz.service;

import com.hys.classcord.quiz.dto.QuizJobStatusResponse;
import com.hys.classcord.quiz.entity.MaterialQuestion;
import com.hys.classcord.quiz.entity.QuizGenerationJob;
import com.hys.classcord.quiz.enums.JobStatus;
import com.hys.classcord.quiz.repository.MaterialQuestionRepository;
import com.hys.classcord.quiz.repository.QuizGenerationJobRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class QuizJobManager {

    private final QuizGenerationJobRepository quizGenerationJobRepository;
    private final MaterialQuestionRepository materialQuestionRepository;
    private final SsePushManager ssePushManager;

    /**
     * 在獨立的新交易 (Propagation.REQUIRES_NEW) 中更新 Job 狀態，並同步推送 SSE 進度事件。 這能保障即使主出題交易阻礙，Job 的 FAILED
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

    /** 在資料庫交易中批次儲存產生的考題，並將背景出題 Job 狀態更新為 COMPLETED。 */
    @Transactional
    public void saveQuestionsAndCompleteJob(
            UUID jobId, UUID materialId, List<MaterialQuestion> questions) {
        // 1. 批次寫入題目庫
        materialQuestionRepository.saveAll(questions);

        // 2. 更新 Job 狀態為 COMPLETED
        QuizGenerationJob job = quizGenerationJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.updateStatus(JobStatus.COMPLETED);
            quizGenerationJobRepository.saveAndFlush(job);
        }

        // 3. 推送最後成功 SSE 通知
        ssePushManager.sendStatusUpdate(
                jobId, new QuizJobStatusResponse(jobId, materialId, JobStatus.COMPLETED, null));
    }
}
