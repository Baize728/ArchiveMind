package com.zyh.archivemind.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String fileMd5;              // 文件指纹
    private Integer chunkId;             // 文本分块序号
    private String textContent;          // 原始文本（传给 LLM 生成）
    private String contextualizedContent; // 增强文本（传给 Reranker 精排）
    private Double score;                // 搜索得分
    private String fileName;             // 原始文件名
    private String userId;               // 上传用户ID
    private String orgTag;               // 组织标签
    private Boolean isPublic;            // 是否公开
    private String docTitle;             // 文档标题
    private String headingPath;          // 章节路径
    private String blockType;            // 内容块类型

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, null, score, null, null, false, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, null, score, null, null, false, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, null, score, userId, orgTag, isPublic, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, String contextualizedContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, contextualizedContent, score, userId, orgTag, isPublic, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, String contextualizedContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, contextualizedContent, score,
                userId, orgTag, isPublic, fileName, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, String contextualizedContent,
                        Double score, String userId, String orgTag, boolean isPublic, String fileName,
                        String docTitle, String headingPath, String blockType) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.contextualizedContent = contextualizedContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.docTitle = docTitle;
        this.headingPath = headingPath;
        this.blockType = blockType;
    }
}
