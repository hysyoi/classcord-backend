package com.hys.classcord.quiz.dto;

import com.hys.classcord.quiz.enums.QuestionType;
import java.util.List;
import java.util.UUID;

public record QuizQuestionReviewResponse(
        UUID id, // quiz_questions.id
        UUID questionId, // material_questions.id
        QuestionType type,
        String question,
        List<String> options,
        List<String> correctAnswer,
        List<String> userAnswer,
        Boolean isCorrect,
        QuestionExplanation explanation) {}
