package com.hys.classcord.quiz.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QuizSubmitRequest(
        @NotNull(message = "答案對照表不能為空")
                Map<UUID, List<String>> answers // Key 是 quiz_questions.id，Value 是學生作答的答案，例如 ["A"]
        ) {}
