package com.zyh.archivemind.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 管理 REST API
 * 仅 ADMIN 角色可访问
 */
@RestController
@RequestMapping("/api/v1/admin/mcp/servers")
public class McpServerController {

    private static final Logger logger = LoggerFactory.getLogger(McpServerController.class);

    private final McpServerConfigRepository repository;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;

    public McpServerController(McpServerConfigRepository repository,
                               McpClientManager mcpClientManager,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.mcpClientManager = mcpClientManager;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<McpServerConfig>> listServers() {
        return ResponseEntity.ok(repository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> addServer(@RequestBody McpServerConfig config) {
        try {
            McpServerConfig saved = repository.save(config);
            mcpClientManager.addServer(saved);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            logger.error("添加 MCP Server 失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateServer(@PathVariable Long id,
                                           @RequestBody McpServerConfig config) {
        try {
            McpServerConfig existing = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("配置不存在: id=" + id));
            mcpClientManager.removeServer(existing.getServerId());

            existing.setServerId(config.getServerId());
            existing.setDisplayName(config.getDisplayName());
            existing.setTransportType(config.getTransportType());
            existing.setCommand(config.getCommand());
            existing.setArgs(config.getArgs());
            existing.setEnvVars(config.getEnvVars());
            existing.setSseUrl(config.getSseUrl());
            existing.setEnabled(config.isEnabled());

            McpServerConfig saved = repository.save(existing);
            if (saved.isEnabled()) {
                mcpClientManager.addServer(saved);
            }
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            logger.error("更新 MCP Server 失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteServer(@PathVariable Long id) {
        try {
            McpServerConfig config = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("配置不存在: id=" + id));
            mcpClientManager.removeServer(config.getServerId());
            repository.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("删除 MCP Server 失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable Long id) {
        try {
            McpServerConfig config = repository.findById(id)
                    .orElseThrow(() -> new RuntimeException("配置不存在: id=" + id));
            McpClientConnection tempConn = new McpClientConnection(config, objectMapper);
            try {
                tempConn.connect();
                Map<String, Object> result = Map.of(
                        "status", "connected",
                        "toolCount", tempConn.getTools().size(),
                        "tools", tempConn.getTools()
                );
                return ResponseEntity.ok(result);
            } finally {
                tempConn.close();
            }
        } catch (Exception e) {
            logger.error("测试 MCP Server 连接失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "failed",
                    "error", e.getMessage()
            ));
        }
    }
}
