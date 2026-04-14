package com.zyh.archivemind.mcp.client;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * MCP Server 配置 Repository
 */
public interface McpServerConfigRepository extends JpaRepository<McpServerConfig, Long> {

    Optional<McpServerConfig> findByServerId(String serverId);

    List<McpServerConfig> findByEnabledTrue();
}
