package com.hys.classcord.ai.enums;

public enum RagStrategyType {
    DEFAULT, // Spring AI 原生預設 (TokenTextSplitter + Standard Embedding)
    CONTEXTUAL, // Contextual Retrieval (Gemini 上下文標籤 + Embedding)
    LATE_CHUNKING // Late Chunking (Jina AI 長向量切片)
}
