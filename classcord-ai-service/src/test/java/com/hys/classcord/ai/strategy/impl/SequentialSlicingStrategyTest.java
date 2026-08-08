package com.hys.classcord.ai.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.hys.classcord.ai.entity.MaterialChunk;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SequentialSlicingStrategyTest {

    private final SequentialSlicingStrategy strategy = new SequentialSlicingStrategy();

    private List<MaterialChunk> chunksOf(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> MaterialChunk.builder().content("chunk-" + i).build())
                .toList();
    }

    // 切片數能整除教材切片數時，應該依序平均分配，不遺漏也不重複任何切片
    @Test
    void slice_evenlyDivides_whenCountDividesChunksExactly() {
        List<String> contexts = strategy.slice(chunksOf(6), 3);

        assertThat(contexts).hasSize(3);
        assertThat(contexts.get(0)).isEqualTo("chunk-0\nchunk-1\n");
        assertThat(contexts.get(1)).isEqualTo("chunk-2\nchunk-3\n");
        assertThat(contexts.get(2)).isEqualTo("chunk-4\nchunk-5\n");
    }

    // 要求的題目數量剛好等於切片數時，每個上下文應該只包含一個切片
    @Test
    void slice_returnsOneChunkPerContext_whenCountEqualsChunkCount() {
        List<String> contexts = strategy.slice(chunksOf(3), 3);

        assertThat(contexts).containsExactly("chunk-0\n", "chunk-1\n", "chunk-2\n");
    }

    // 要求的題目數量超過教材切片數時（教材很短、但想出很多題），邊界保護應該確保
    // 不會產生任何空字串的上下文，即使得重複利用同一批切片
    @Test
    void slice_producesNoEmptyContexts_whenCountExceedsChunkCount() {
        List<String> contexts = strategy.slice(chunksOf(2), 5);

        assertThat(contexts).hasSize(5);
        assertThat(contexts).allSatisfy(context -> assertThat(context).isNotBlank());
    }

    // 教材只有一個切片、卻要求出多道題目時，每個上下文都應該安全地退回使用唯一的那個切片
    @Test
    void slice_reusesOnlyChunk_whenOnlyOneChunkAvailable() {
        List<String> contexts = strategy.slice(chunksOf(1), 3);

        assertThat(contexts).hasSize(3);
        assertThat(contexts).allSatisfy(context -> assertThat(context).isEqualTo("chunk-0\n"));
    }
}
