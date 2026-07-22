package com.hys.classcord.material.enums;

public enum MaterialStatus {
    DISABLED, // 未啟用 AI 助教
    PROCESSING, // RAG 切片與向量化處理中
    ENABLED, // 已啟用 AI 助教，可供問答
    FAILED // 處理失敗
}
