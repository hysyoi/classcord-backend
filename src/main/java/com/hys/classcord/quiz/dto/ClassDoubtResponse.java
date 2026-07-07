package com.hys.classcord.quiz.dto;

import java.util.List;

public record ClassDoubtResponse(int totalQuestionsAnalyzed, List<DoubtTheme> themes) {}
