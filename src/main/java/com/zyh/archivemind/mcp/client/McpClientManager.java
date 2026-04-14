package com.zyh.archivemind.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.ToolDefinition;
import com.zyh.archivemind.skill.SkillResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 管理器
 * 管理所有外部 MCP Server 的连接，提供工具发现和工具调用的统一入口
 */
@Component
public class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpClientConnection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public McpClientManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void addServer(McpServerConfig config) {
        if (connections.containsKey(config.getServerId())) {
            logger.warn("MCP Server 已存在，跳过: {}", config.getServerId());
            return;
        }
        try {
            McpClientConnection connection = new McpClientConnection(config, objectMapper);
            connection.connect();
            connections.put(config.getServerId(), connection);
            logger.info("MCP Server 添加成功: {}, 工具数: {}",
                    config.getServerId(), connection.getTools().size());
        } catch (Exception e) {
            logger.error("连接 MCP Server 失败: {}, 错误: {}",
                    config.getServerId(), e.getMessage(), e);
        }
    }

    public void removeServer(String serverId) {
        McpClientConnection connection = connections.remove(serverId);
        if (connection != null) {
            connection.close();
            logger.info("MCP Server 已移除: {}", serverId);
        }
    }

    /**
     * 发现所有已连接 Server 的工具，名称加 "serverId:" 前缀
     */
    public List<ToolDefinition> discoverAllTools() {
        List<ToolDefinition> allTools = new ArrayList<>();
        for (Map.Entry<String, McpClientConnection> entry : connections.entrySet()) {
            String serverId = entry.getKey();
            McpClientConnection conn = entry.getValue();
            if (conn.isConnected()) {
                for (ToolDefinition tool : conn.getTools()) {
                    allTools.add(ToolDefinition.builder()
                            .name(serverId + ":" + tool.getName())
                            .description("[" + serverId + "] " + tool.getDescription())
                            .parameters(tool.getParameters())
                            .build());
                }
            }
        }
        return allTools;
    }

    /**
     * 调用指定 Server 的工具
     */
    public SkillResult callTool(String serverId, String toolName, Map<String, Object> params) {
        McpClientConnection connection = connections.get(serverId);
        if (connection == null || !connection.isConnected()) {
            return SkillResult.failure("MCP Server 未连接: " + serverId);
        }
        try {
            String result = connection.callTool(toolName, params);
            return SkillResult.success(result);
        } catch (Exception e) {
            logger.error("调用 MCP Tool 失败: {}:{}, 错误: {}",
                    serverId, toolName, e.getMessage(), e);
            return SkillResult.failure("工具调用失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(McpClientConnection::close);
        connections.clear();
        logger.info("所有 MCP Client 连接已关闭");
    }
}
