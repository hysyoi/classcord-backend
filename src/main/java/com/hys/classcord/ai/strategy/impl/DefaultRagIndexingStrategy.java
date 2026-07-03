package com.hys.classcord.ai.strategy.impl;

import com.hys.classcord.ai.enums.RagStrategyType;
import com.hys.classcord.ai.strategy.RagIndexingStrategy;
import com.hys.classcord.material.entity.Material;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRagIndexingStrategy implements RagIndexingStrategy {

    private final VectorStore vectorStore;

    @Override
    public RagStrategyType getType() {
        return RagStrategyType.DEFAULT;
    }

    @Override
    public void processAndIndex(Material material, byte[] fileBytes) {
        log.info("開始使用 DEFAULT 策略解析教材 RAG: materialId={}", material.getId());

        // 1. 使用 Spring AI 的 TikaDocumentReader 解析檔案 (通用解析 PDF, Docx, MD 等格式)
        ByteArrayResource resource = new ByteArrayResource(fileBytes);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> rawDocuments = reader.get();

        // 2. 使用 TokenTextSplitter 進行語意切片 (預設 ChunkSize 800 tokens, Overlap 350)
        TokenTextSplitter splitter = new TokenTextSplitter(800, 350, 100, 10000, true);
        List<Document> splitDocuments = splitter.apply(rawDocuments);

        // 3. 超前部署：注入多租戶過濾所需 Metadata (server_id, channel_id, material_id)
        String serverId = material.getMessage().getChannel().getServer().getId().toString();
        String channelId = material.getMessage().getChannel().getId().toString();
        String materialId = material.getId().toString();
        String fileName = material.getOriginalName();

        for (int i = 0; i < splitDocuments.size(); i++) {
            Document doc = splitDocuments.get(i);
            Map<String, Object> metadata = doc.getMetadata();
            // 覆蓋 Spring AI 因為讀取 ByteArrayResource 失敗而寫入的罐頭錯誤字串 "Invalid source URI..."
            metadata.put("source", fileName);
            metadata.put("server_id", serverId);
            metadata.put("channel_id", channelId);
            metadata.put("material_id", materialId);
            metadata.put("chunk_index", i);
        }

        // 4. 寫入向量資料庫 (VectorStore 會自動呼叫 EmbeddingModel 並存入 PostgreSQL pgvector)
        vectorStore.accept(splitDocuments);
        log.info(
                "DEFAULT 策略向量化完成，共生成 {} 個 Chunks: materialId={}",
                splitDocuments.size(),
                material.getId());
    }
}
