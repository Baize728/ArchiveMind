package com.zyh.archivemind.mcp.client;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MCP Server 配置 JPA 实体
 * 对应 mcp_server_config 数据库表，存储外部 MCP Server 的连接配置信息
 */
@Entity
@Table(name = "mcp_server_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", unique = true, nullable = false)
    private String serverId;

    @Column(name = "display_name")
    private String displayName;

    @Builder.Default
    @Column(name = "transport_type", nullable = false)
    private String transportType = "stdio";

    @Column(nullable = false)
    private String command;

    @Column(columnDefinition = "JSON")
    private String args;

    @Column(name = "env_vars", columnDefinition = "JSON")
    private String envVars;

    @Column(name = "sse_url")
    private String sseUrl;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
