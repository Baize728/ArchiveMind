package com.zyh.archivemind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 解析结果缓存表。
 * 按文件内容指纹和解析器版本缓存最终进入 chunk pipeline 的 Markdown。
 */
@Data
@Entity
@Table(
        name = "parsed_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_parsed_document_version",
                columnNames = {"file_md5", "parser_type", "parser_version", "parser_config_hash"}
        ),
        indexes = {
                @Index(name = "idx_parsed_documents_file_md5", columnList = "file_md5"),
                @Index(name = "idx_parsed_documents_status", columnList = "parse_status")
        }
)
public class ParsedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "parser_type", nullable = false, length = 64)
    private String parserType;

    @Column(name = "parser_version", nullable = false, length = 128)
    private String parserVersion;

    @Column(name = "parser_config_hash", nullable = false, length = 64)
    private String parserConfigHash;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_extension", length = 32)
    private String fileExtension;

    @Lob
    @Column(name = "markdown_content", columnDefinition = "LONGTEXT")
    private String markdownContent;

    @Column(name = "markdown_length", nullable = false)
    private int markdownLength;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "language", length = 32)
    private String language;

    @Column(name = "parse_status", nullable = false, length = 32)
    private String parseStatus;

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
