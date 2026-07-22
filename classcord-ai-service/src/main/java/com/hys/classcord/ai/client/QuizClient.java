package com.hys.classcord.ai.client;

import com.hys.classcord.common.dto.FailMaterialRequest;
import com.hys.classcord.quiz.dto.SaveGeneratedQuestionsRequest;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "classcord-main-service",
        contextId = "quizClient",
        path = "/api/internal/quizzes")
public interface QuizClient {

    @PutMapping("/jobs/{jobId}/complete")
    void completeJob(
            @PathVariable("jobId") UUID jobId, @RequestBody SaveGeneratedQuestionsRequest request);

    @PutMapping("/jobs/{jobId}/failed")
    void markJobAsFailed(
            @PathVariable("jobId") UUID jobId,
            @RequestParam("materialId") UUID materialId,
            @RequestBody(required = false) FailMaterialRequest request);
}
