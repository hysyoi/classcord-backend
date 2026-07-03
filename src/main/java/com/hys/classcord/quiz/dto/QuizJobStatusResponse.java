package com.hys.classcord.quiz.dto;

import com.hys.classcord.quiz.enums.JobStatus;
import java.util.UUID;

public record QuizJobStatusResponse(
        UUID jobId, UUID materialId, JobStatus status, String errorMessage) {}
