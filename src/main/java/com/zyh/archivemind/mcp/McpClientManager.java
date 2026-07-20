package com.zyh.archivemind.mcp;

import com.zyh.archivemind.Tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 生命周期管理器
 * <p>
 * 职责：
 * - 启动时根据配置连接所有 MCP Server
 * - 发现并注册 MCP 工具到 ToolRegistry
 * - 关闭时注销工具并关闭所有客户端连接
 * </p>
 */
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpClientManager {

    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final McpProperties mcpProperties;
    private final ToolRegistry toolRegistry;

    /** 活跃的 MCP 客户端，key 为 server 名称 */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** 每个 server 注册的工具名称集合，key 为 server 名称 */
    private final Map<String, Set<String>> serverTools = new ConcurrentHashMap<>();

    public McpClientManager(McpProperties mcpProperties, ToolRegistry toolRegistry) {
        this.mcpProperties = mcpProperties;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initialize() {
        if (!mcpProperties.isEnabled()) {
            logger.info("MCP 支持已禁用（mcp.enabled=false）");
            return;
        }

        Map<String, McpProperties.ServerConfig> servers = mcpProperties.getServers();
        if (servers == null || servers.isEmpty()) {
            logger.info("未配置任何 MCP Server");
            return;
        }

        servers.forEach((serverName, config) -> {
            if (!config.isEnabled()) {
                logger.info("MCP Server '{}' 已禁用，跳过", serverName);
                return;
            }
            connectAndRegister(serverName, config);
        });

        logger.info("MCP Client Manager 初始化完成，共连接 {} 个 Server", clients.size());
    }

    private void connectAndRegister(String serverName, McpProperties.ServerConfig config) {
        int maxAttempts = config.getReconnectAttempts() + 1; // +1 for initial attempt
        McpSyncClient client = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                McpClientTransport transport = McpTransportFactory.create(config);
                client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                        .build();
                client.initialize();
                logger.info("MCP Server '{}' 连接成功 (type={})", serverName, config.getType());
                break;
            } catch (Exception e) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                    client = null;
                }
                if (attempt < maxAttempts - 1) {
                    logger.warn("MCP Server '{}' 连接失败 (第 {} 次尝试)，{}s 后重试: {}",
                            serverName, attempt + 1, config.getReconnectDelaySeconds(), e.getMessage());
                    try {
                        Thread.sleep(config.getReconnectDelaySeconds() * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("重试被中断，跳过 Server '{}'", serverName);
                        return;
                    }
                } else {
                    logger.error("MCP Server '{}' 连接失败（共 {} 次尝试），跳过: {}",
                            serverName, maxAttempts, e.getMessage());
                    return;
                }
            }
        }

        if (client == null) {
            return;
        }

        // 发现并注册工具
        try {
            List<McpSchema.Tool> allTools = listAllTools(client);
            if (allTools.isEmpty()) {
                logger.info("MCP Server '{}' 未提供任何工具", serverName);
            }

            Set<String> registeredNames = new HashSet<>();
            for (McpSchema.Tool tool : allTools) {
                McpToolAdapter adapter = new McpToolAdapter(
                        serverName, tool, client, config.getRequestTimeoutSeconds());
                boolean success = toolRegistry.register(adapter);
                if (success) {
                    registeredNames.add(adapter.getName());
                }
            }

            clients.put(serverName, client);
            serverTools.put(serverName, registeredNames);
            logger.info("MCP Server '{}': 注册了 {} 个工具", serverName, registeredNames.size());
        } catch (Exception e) {
            logger.error("MCP Server '{}' 工具发现失败: {}", serverName, e.getMessage());
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 列出所有工具，处理分页
     */
    private List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> allTools = new ArrayList<>();
        McpSchema.ListToolsResult result = client.listTools();
        allTools.addAll(result.tools());
        String cursor = result.nextCursor();
        while (cursor != null && !cursor.isEmpty()) {
            result = client.listTools(cursor);
            allTools.addAll(result.tools());
            cursor = result.nextCursor();
        }
        return allTools;
    }

    @PreDestroy
    public void shutdown() {
        // 注销所有 MCP 工具
        serverTools.forEach((serverName, toolNames) -> {
            for (String toolName : toolNames) {
                toolRegistry.unregister(toolName);
            }
            logger.info("已注销 MCP Server '{}' 的全部 {} 个工具", serverName, toolNames.size());
        });
        serverTools.clear();

        // 关闭所有客户端
        clients.forEach((serverName, client) -> {
            try {
                client.close();
                logger.info("已关闭 MCP Server '{}' 的连接", serverName);
            } catch (Exception e) {
                logger.warn("关闭 MCP Server '{}' 时出错: {}", serverName, e.getMessage());
            }
        });
        clients.clear();

        logger.info("MCP Client Manager 已关闭");
    }

    /**
     * 运行时刷新指定 Server 的工具列表
     */
    public void refreshTools(String serverName) {
        McpSyncClient client = clients.get(serverName);
        if (client == null) {
            logger.warn("无法刷新: MCP Server '{}' 不存在或未连接", serverName);
            return;
        }

        // 先注销旧工具
        Set<String> oldTools = serverTools.remove(serverName);
        if (oldTools != null) {
            for (String toolName : oldTools) {
                toolRegistry.unregister(toolName);
            }
        }

        // 重新发现并注册
        McpProperties.ServerConfig config = mcpProperties.getServers().get(serverName);
        int timeout = config != null ? config.getRequestTimeoutSeconds() : 30;
        try {
            List<McpSchema.Tool> allTools = listAllTools(client);
            Set<String> newNames = new HashSet<>();
            for (McpSchema.Tool tool : allTools) {
                McpToolAdapter adapter = new McpToolAdapter(serverName, tool, client, timeout);
                if (toolRegistry.register(adapter)) {
                    newNames.add(adapter.getName());
                }
            }
            serverTools.put(serverName, newNames);
            logger.info("MCP Server '{}' 工具已刷新: {} -> {} 个", serverName,
                    oldTools != null ? oldTools.size() : 0, newNames.size());
        } catch (Exception e) {
            logger.error("刷新 MCP Server '{}' 工具失败: {}", serverName, e.getMessage());
        }
    }
}
