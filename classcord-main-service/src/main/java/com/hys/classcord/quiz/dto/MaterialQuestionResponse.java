package com.hys.classcord.quiz.dto;

import com.hys.classcord.quiz.enums.QuestionType;
import java.util.List;
import java.util.UUID;

public record MaterialQuestionResponse(
        UUID id,
        QuestionType type,
        String question,
        List<String> options,
        List<String> correctAnswer,
        QuestionExplanation explanation) {}
