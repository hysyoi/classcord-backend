package com.hys.classcord.quiz.dto;

import com.hys.classcord.quiz.enums.QuestionType;
import java.util.List;
import java.util.UUID;

public record QuizQuestionResponse(
        UUID id, // 作答明細的 ID (quiz_questions.id)
        UUID questionId, // 題庫題目的 ID (material_questions.id)
        QuestionType type,
        String question,
        List<String> options) {}
