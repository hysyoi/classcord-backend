package com.hys.classcord.ai.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateSessionRequest(@NotNull(message = "教材 ID 不能為空") UUID materialId) {}
