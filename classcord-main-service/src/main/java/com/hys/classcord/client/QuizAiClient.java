package com.hys.classcord.client;

import com.hys.classcord.quiz.dto.ClassDoubtResponse;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "classcord-ai-service", contextId = "quizAiClient", path = "/api/internal/ai/quizzes")
public interface QuizAiClient {

    @PostMapping("/doubt-analysis")
    ClassDoubtResponse analyzeDoubt(@RequestParam("materialId") UUID materialId);
}
