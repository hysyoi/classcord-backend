package com.hys.classcord.quiz.controller;

import com.hys.classcord.common.dto.FailMaterialRequest;
import com.hys.classcord.quiz.dto.SaveGeneratedQuestionsRequest;
import com.hys.classcord.quiz.enums.JobStatus;
import com.hys.classcord.quiz.service.QuizJobManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/internal/quizzes")
@RequiredArgsConstructor
@Tag(name = "Internal Quiz Controller", description = "內部微服務調用的 Quiz API")
public class InternalQuizController {

    private final QuizJobManager quizJobManager;

    @PutMapping("/jobs/{jobId}/complete")
    @Operation(summary = "標記 Quiz 出題 Job 完成並儲存題目", description = "供 AI 微服務完成出題後呼叫，委託 Service 入庫寫入題目並推送 SSE")
    public ResponseEntity<Void> completeJob(
            @PathVariable UUID jobId,
            @RequestBody SaveGeneratedQuestionsRequest request) {
        log.info("【內部 API】收到 AI 服務回傳出題完成通知: jobId={}, materialId={}", jobId, request.materialId());
        quizJobManager.saveGeneratedQuestionsAndComplete(jobId, request);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/jobs/{jobId}/failed")
    @Operation(summary = "標記 Quiz 出題 Job 為 FAILED", description = "供 AI 微服務出題失敗時呼叫，委託 Service 寫入失敗訊息並推送 SSE")
    public ResponseEntity<Void> markJobAsFailed(
            @PathVariable UUID jobId,
            @RequestParam UUID materialId,
            @RequestBody(required = false) FailMaterialRequest request) {
        String errorMessage = request != null ? request.errorMessage() : "出題過程發生未預期錯誤";
        log.error("【內部 API】收到 AI 服務回傳出題失敗通知: jobId={}, materialId={}, error={}", jobId, materialId, errorMessage);
        quizJobManager.updateJobStatusAndPushRequiresNew(jobId, materialId, JobStatus.FAILED, errorMessage);
        return ResponseEntity.ok().build();
    }
}
