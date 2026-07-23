package com.hys.classcord.quiz.dto;

import java.util.List;

public record DoubtTheme(
        String themeName, String description, List<String> questions, String recommendation) {}
