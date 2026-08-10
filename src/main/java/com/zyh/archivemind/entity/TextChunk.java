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
    private String docTitle;                // 文档标题
    private String headingPath;             // 章节路径
    private String blockType;               // 内容块类型
    private Integer startOffset;            // Markdown 起始位置
    private Integer endOffset;              // Markdown 结束位置
    private Integer tokenLength;            // 近似 token 长度

    public TextChunk(int chunkId, String content) {
        this(chunkId, content, null);
    }

    public TextChunk(int chunkId, String content, String contextualizedContent) {
        this(chunkId, content, contextualizedContent, null, null, null, null, null, null);
    }

    public TextChunk(int chunkId, String content, String contextualizedContent,
                     String docTitle, String headingPath, String blockType,
                     Integer startOffset, Integer endOffset, Integer tokenLength) {
        this.chunkId = chunkId;
        this.content = content;
        this.contextualizedContent = contextualizedContent;
        this.docTitle = docTitle;
        this.headingPath = headingPath;
        this.blockType = blockType;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.tokenLength = tokenLength;
    }
}
