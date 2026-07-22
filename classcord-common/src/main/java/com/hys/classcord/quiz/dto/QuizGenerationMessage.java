package com.hys.classcord.quiz.dto;

import java.io.Serializable;
import java.util.UUID;

public record QuizGenerationMessage(
        UUID jobId,
        UUID materialId,
        int count,
        String difficulty
) implements Serializable {}
