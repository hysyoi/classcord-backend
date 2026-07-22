package com.hys.classcord.quiz.dto;

import java.util.List;

public record GeneratedQuestionDto(
        String question,
        List<String> options,
        List<String> correctAnswer,
        QuestionExplanation explanation
) {}
