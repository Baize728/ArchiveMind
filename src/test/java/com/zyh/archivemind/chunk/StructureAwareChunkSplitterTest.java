package com.zyh.archivemind.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareChunkSplitterTest {

    private final StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter();

    @Test
    void shouldNotMergeChunksAcrossHeadings() {
        String markdown = """
                # Java 并发

                ## 线程池

                线程池可以复用线程，避免频繁创建线程。

                ## 锁机制

                synchronized 和 ReentrantLock 都可以保护临界区。
                """;

        List<ChunkUnit> chunks = splitter.split(markdown, 80, 0);

        assertEquals(2, chunks.size());
        assertEquals("Java 并发 > 线程池", chunks.get(0).headingPath());
        assertEquals("Java 并发 > 锁机制", chunks.get(1).headingPath());
        assertFalse(chunks.get(0).content().contains("锁机制"));
    }

    @Test
    void shouldKeepMarkdownTableAsTableBlock() {
        String markdown = """
                # 配置说明

                ## 参数表

                | 参数 | 说明 |
                | --- | --- |
                | chunkSize | 分块大小 |
                | overlap | 重叠长度 |
                """;

        List<ChunkUnit> chunks = splitter.split(markdown, 200, 0);

        assertEquals(1, chunks.size());
        assertEquals("table", chunks.get(0).blockType());
        assertTrue(chunks.get(0).content().contains("| chunkSize | 分块大小 |"));
        assertEquals("配置说明 > 参数表", chunks.get(0).headingPath());
    }
}
