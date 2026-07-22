package com.hys.classcord.quiz.dto;

import java.util.List;
import java.util.UUID;

public record LightweightQuizQuestionDto(
        UUID questionId, Boolean isCorrect, List<String> userAnswer) {}
