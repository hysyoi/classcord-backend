package com.hys.classcord.quiz.dto;

import java.util.Map;

public record QuestionExplanation(
        String general, // 綜合解析
        Map<String, String> options // 各個選項（A, B, C, D）的個別說明
) {}
