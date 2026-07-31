package com.zyh.archivemind.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
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
     * 父块 ID，用于 Small-to-Big 检索时回溯父块上下文。
     * 同一父块下的子块共享相同的 parentChunkId。
     */
    @Column(name = "parent_chunk_id")
    private Integer parentChunkId;

    /**
     * 父块完整文本内容（建议 2048-4096 字符）。
     * 检索时子块命中后，通过此字段回取父块完整上下文给 LLM。
     */
    @Lob
    @Column(name = "parent_text")
    private String parentText;
}