
package com.zyh.archivemind.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号
    private String textContent;              // 原始文本（传给 LLM 生成）
    private String contextualizedContent;    // 上下文增强文本（用于检索：KNN+BM25）
    private String docTitle;                 // 文档标题
    private String headingPath;              // 章节路径
    private String blockType;                // 内容块类型
    private Integer startOffset;             // Markdown 起始位置
    private Integer endOffset;               // Markdown 结束位置
    private Integer tokenLength;             // 近似 token 长度
    private float[] vector;                  // 向量数据（2048维）
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    @JsonProperty("isPublic")
    private boolean isPublic;      // 是否公开

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, null, vector, modelVersion, userId, orgTag, isPublic);
    }

    /**
     * 完整构造函数，包含上下文增强内容。
     */
    public EsDocument(String id, String fileMd5, int chunkId, String textContent,
                     String contextualizedContent,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, textContent, contextualizedContent,
                null, null, null, null, null, null,
                vector, modelVersion, userId, orgTag, isPublic);
    }

    /**
     * 完整构造函数，包含上下文增强内容和结构元数据。
     */
    public EsDocument(String id, String fileMd5, int chunkId, String textContent,
                     String contextualizedContent,
                     String docTitle, String headingPath, String blockType,
                     Integer startOffset, Integer endOffset, Integer tokenLength,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.contextualizedContent = contextualizedContent;
        this.docTitle = docTitle;
        this.headingPath = headingPath;
        this.blockType = blockType;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.tokenLength = tokenLength;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }

    @JsonProperty("isPublic")
    public boolean isPublic() {
        return isPublic;
    }

    @JsonProperty("isPublic")
    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

}
