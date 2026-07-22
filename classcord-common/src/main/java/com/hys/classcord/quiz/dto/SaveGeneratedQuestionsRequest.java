package com.hys.classcord.quiz.dto;

import java.util.List;
import java.util.UUID;

public record SaveGeneratedQuestionsRequest(
        UUID jobId, UUID materialId, List<GeneratedQuestionDto> questions) {}
