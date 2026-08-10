package com.zyh.archivemind.chunk;

/**
 * 结构感知切割后的最小检索单元。
 */
public record ChunkUnit(
        String content,
        String docTitle,
        String headingPath,
        String blockType,
        int startOffset,
        int endOffset,
        int tokenLength
) {
}
