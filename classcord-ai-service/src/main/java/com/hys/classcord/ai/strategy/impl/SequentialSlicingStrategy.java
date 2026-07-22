package com.hys.classcord.ai.strategy.impl;

import com.hys.classcord.ai.entity.MaterialChunk;
import com.hys.classcord.ai.strategy.MaterialSlicingStrategy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SequentialSlicingStrategy implements MaterialSlicingStrategy {

    @Override
    public List<String> slice(List<MaterialChunk> chunks, int count) {
        List<String> contexts = new ArrayList<>();
        int totalChunks = chunks.size();

        for (int i = 0; i < count; i++) {
            int startIdx = i * totalChunks / count;
            int endIdx = (i + 1) * totalChunks / count;

            if (endIdx > totalChunks) {
                endIdx = totalChunks;
            }

            // 邊界保護：如果因為 totalChunks 太短使得 startIdx >= endIdx，強制作出安全範圍，確保不產生空 slice
            if (startIdx >= endIdx) {
                endIdx = Math.min(totalChunks, startIdx + 1);
                if (startIdx >= endIdx) {
                    startIdx = Math.max(0, totalChunks - 1);
                    endIdx = totalChunks;
                }
            }

            StringBuilder sb = new StringBuilder();
            for (int k = startIdx; k < endIdx; k++) {
                sb.append(chunks.get(k).getContent()).append("\n");
            }
            contexts.add(sb.toString());
        }

        return contexts;
    }
}
