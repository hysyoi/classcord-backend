package com.hys.classcord.quiz.controller;

import com.hys.classcord.quiz.dto.*;
import com.hys.classcord.quiz.service.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "AI 測驗模組", description = "提供教師 AI 生成/管理題庫，以及學生測驗作答的 API")
public class QuizController {

    private final QuizService quizService;

    @PostMapping("/materials/{materialId}/questions/generate")
    @Operation(summary = "產生教材題庫", description = "教師/助教：AI 根據教材切片物理順序分段平行出題 (異步排隊，防刷上限 50 題)")
    public ResponseEntity<QuizJobStatusResponse> generateQuestions(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID materialId,
            @RequestParam(defaultValue = "5") int count,
            @RequestParam(defaultValue = "MEDIUM") String difficulty) {
        var response =
                quizService.triggerQuestionsGeneration(userId, materialId, count, difficulty);
        return ResponseEntity.ok(response);
    }

    @GetMapping(
            value = "/materials/questions/tasks/{jobId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "訂閱出題任務狀態", description = "前端透過 SSE 監聽背景 AI 出題任務的進度更新")
    public SseEmitter streamJobStatus(@PathVariable UUID jobId) {
        return quizService.registerSseStream(jobId);
    }

    @GetMapping("/materials/{materialId}/questions")
    @Operation(summary = "查看教材題庫", description = "教師/助教：查詢該教材所有有效的題目列表（審核用）")
    public ResponseEntity<List<MaterialQuestionResponse>> getQuestionPool(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID materialId) {
        var response = quizService.getMaterialQuestionPool(userId, materialId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/materials/questions/{questionId}")
    @Operation(summary = "刪除題庫考題", description = "教師/助教：軟刪除題庫中的考題（不影響學生歷史作答報告）")
    public ResponseEntity<Map<String, String>> deleteQuestion(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID questionId) {
        quizService.deleteMaterialQuestion(userId, questionId);
        return ResponseEntity.ok(Map.of("message", "考題刪除成功"));
    }

    @GetMapping("/materials/{materialId}/quizzes")
    @Operation(summary = "查詢歷史測驗紀錄", description = "學生：查詢該教材自己所有的歷史測驗紀錄列表")
    public ResponseEntity<List<QuizResponse>> getUserQuizzes(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID materialId) {
        var response = quizService.getUserMaterialQuizzes(userId, materialId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/materials/{materialId}/quizzes")
    @Operation(summary = "開始測驗", description = "學生：隨機抽取題庫中 10 題建立測驗（隱藏標準答案與解析）")
    public ResponseEntity<QuizResponse> createQuiz(
            @AuthenticationPrincipal UUID studentId, @PathVariable UUID materialId) {
        var response = quizService.createQuiz(studentId, materialId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/quizzes/{quizId}/submit")
    @Operation(summary = "提交測驗作答", description = "學生：提交考卷答案，系統自動批改、計分並回傳詳解評估報告")
    public ResponseEntity<QuizSubmitResponse> submitQuiz(
            @AuthenticationPrincipal UUID studentId,
            @PathVariable UUID quizId,
            @Valid @RequestBody QuizSubmitRequest request) {
        var response = quizService.submitQuiz(studentId, quizId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/quizzes/{quizId}")
    @Operation(summary = "查詢測驗報告", description = "學生/教師：查詢該次測驗的得分、學生答案與標準答案詳解對照表")
    public ResponseEntity<QuizSubmitResponse> getQuizReport(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID quizId) {
        var response = quizService.getQuizReport(userId, quizId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/materials/{materialId}/analysis/wrong-questions")
    @Operation(summary = "獲取班級錯題分析", description = "教師/助教：獲取教材的錯題統計，含答錯次數、錯誤率及選項分佈，由高到低排序")
    public ResponseEntity<List<ClassWrongQuestionResponse>> getClassWrongQuestionAnalysis(
            @AuthenticationPrincipal UUID userId, @PathVariable UUID materialId) {
        var response = quizService.getClassWrongQuestionAnalysis(userId, materialId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/materials/{materialId}/analysis/doubts")
    @Operation(
            summary = "獲取班級疑問分析",
            description = "教師/助教：利用 AI 分析最近 50 筆學生對該教材的提問，過濾日常閒聊並分組生成疑惑主題與建議")
    public ResponseEntity<ClassDoubtResponse> getClassDoubtAnalysis(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID materialId,
            @RequestParam(value = "regenerate", defaultValue = "false") boolean regenerate) {
        var response = quizService.getClassDoubtAnalysis(userId, materialId, regenerate);
        return ResponseEntity.ok(response);
    }
}
