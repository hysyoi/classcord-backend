package com.hys.classcord.ai.strategy;

import com.hys.classcord.ai.enums.RagStrategyType;
import com.hys.classcord.common.dto.InternalMaterialDto;

public interface RagIndexingStrategy {

    // 取得當前策略類型
    RagStrategyType getType();

    // 核心處理方法：接收教材 DTO 與從 B2 下載的檔案位元組，進行解析、切片與向量化
    void processAndIndex(InternalMaterialDto material, byte[] fileBytes);
}
