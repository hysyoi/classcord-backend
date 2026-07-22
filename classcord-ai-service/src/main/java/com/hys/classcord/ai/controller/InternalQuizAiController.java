package com.hys.classcord.ai.controller;

import com.hys.classcord.ai.service.QuizAiService;
import com.hys.classcord.quiz.dto.ClassDoubtResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Tag(name = "內部 AI 測驗與疑問分析 API", description = "僅供微服務內部 (main-service -> ai-service) 調用之 AI 測驗與班級疑難點分析端點")
@Slf4j
@RestController
@RequestMapping("/api/internal/ai/quizzes")
@RequiredArgsConstructor
public class InternalQuizAiController {

    private final QuizAiService quizAiService;

    @Operation(summary = "執行班級疑問 AI 分析", description = "讀取指定教材的學生提問歷史，並由 Gemini 大模型進行結構化疑問主題分析")
    @PostMapping("/doubt-analysis")
    public ClassDoubtResponse analyzeDoubt(@RequestParam("materialId") UUID materialId) {
        log.info("【內部 API】收到 AI 疑難點分析請求: materialId={}", materialId);
        return quizAiService.analyzeDoubt(materialId);
    }
}
