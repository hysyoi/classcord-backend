package com.hys.classcord.quiz.dto;

import com.hys.classcord.quiz.enums.QuestionType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ClassWrongQuestionResponse(
        UUID questionId,
        QuestionType type,
        String question,
        List<String> options,
        List<String> correctAnswer,
        QuestionExplanation explanation,
        int totalAttempts,
        int wrongAttempts,
        double errorRate,
        Map<String, Integer> optionDistribution) {}
