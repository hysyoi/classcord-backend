package com.hys.classcord.quiz.strategy;

import com.hys.classcord.ai.entity.MaterialChunk;
import java.util.List;

public interface MaterialSlicingStrategy {
    /**
     * 根據教材切片，切分出 count 個用於出題的上下文文本
     *
     * @param chunks 物理順序排列的教材切片
     * @param count 欲生成的題目數量
     * @return count 個文本上下文
     */
    List<String> slice(List<MaterialChunk> chunks, int count);
}
