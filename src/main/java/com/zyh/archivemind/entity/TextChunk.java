package com.zyh.archivemind.entity;

import lombok.Getter;
import lombok.Setter;

// 文件分块内容实体类
@Setter
@Getter
public class TextChunk {

    private int chunkId;                    // 分块序号
    private String content;                 // 原始文本内容
    private String contextualizedContent;   // 上下文增强后的文本（用于检索）

    public TextChunk(int chunkId, String content) {
        this(chunkId, content, null);
    }

    public TextChunk(int chunkId, String content, String contextualizedContent) {
        this.chunkId = chunkId;
        this.content = content;
        this.contextualizedContent = contextualizedContent;
    }
}
