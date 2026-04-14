package com.zyh.archivemind.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Server 启动加载器
 * 应用启动时从数据库读取已启用的 MCP Server 配置并建立连接
 */
@Component
public class McpServerInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(McpServerInitializer.class);

    private final McpServerConfigRepository repository;
    private final McpClientManager mcpClientManager;

    public McpServerInitializer(McpServerConfigRepository repository,
                                McpClientManager mcpClientManager) {
        this.repository = repository;
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<McpServerConfig> configs = repository.findByEnabledTrue();
        if (configs.isEmpty()) {
            logger.info("未配置任何 MCP Server，跳过初始化");
            return;
        }
        logger.info("开始加载 {} 个 MCP Server 配置", configs.size());
        for (McpServerConfig config : configs) {
            try {
                mcpClientManager.addServer(config);
            } catch (Exception e) {
                logger.error("启动时连接 MCP Server 失败: serverId={}, 错误: {}",
                        config.getServerId(), e.getMessage(), e);
            }
        }
    }
}
