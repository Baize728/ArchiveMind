package com.zyh.archivemind.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(
        name = "document_vectors",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_vectors_file_user_chunk",
                columnNames = {"file_md5", "user_id", "chunk_id"}
        ),
        indexes = {
                @Index(name = "idx_document_vectors_file_user", columnList = "file_md5,user_id"),
                @Index(name = "idx_document_vectors_permission", columnList = "user_id,org_tag,is_public")
        }
)
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /**
     * 上下文增强后的文本内容（上下文前缀 + 原始 chunk）。
     * 用于 embedding 和 BM25 检索，原始文本仍由 textContent 保留。
     */
    @Lob
    @Column(name = "contextualized_content", columnDefinition = "LONGTEXT")
    private String contextualizedContent;

    /**
     * 文档标题，来自结构感知 Markdown 解析。
     */
    @Column(name = "doc_title", length = 512)
    private String docTitle;

    /**
     * 当前 chunk 所属章节路径，例如：文档标题 > 一级标题 > 二级标题。
     */
    @Column(name = "heading_path", length = 1024)
    private String headingPath;

    /**
     * 内容块类型：paragraph/list/table/code/mixed。
     */
    @Column(name = "block_type", length = 32)
    private String blockType;

    @Column(name = "start_offset")
    private Integer startOffset;

    @Column(name = "end_offset")
    private Integer endOffset;

    @Column(name = "token_length")
    private Integer tokenLength;
}
