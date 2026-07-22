package com.hys.classcord.quiz.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuizSubmitResponse(
        UUID id,
        UUID materialId,
        Integer score,
        Instant createdAt,
        List<QuizQuestionReviewResponse> reviews) {}
