package com.zyh.archivemind.mcp;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 传输层工厂
 * 根据配置创建对应的 McpClientTransport（Stdio 或 SSE）
 */
public final class McpTransportFactory {

    private static final Logger logger = LoggerFactory.getLogger(McpTransportFactory.class);

    private McpTransportFactory() {
    }

    /**
     * 根据配置创建传输层
     *
     * @param config Server 配置
     * @return McpClientTransport 实例
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    public static McpClientTransport create(McpProperties.ServerConfig config) {
        if ("sse".equalsIgnoreCase(config.getType())) {
            return createSseTransport(config);
        }
        return createStdioTransport(config);
    }

    private static McpClientTransport createSseTransport(McpProperties.ServerConfig config) {
        String url = config.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("SSE 传输需要配置 'url'");
        }
        logger.info("创建 SSE 传输: url={}", url);
        return HttpClientSseClientTransport.builder(url).build();
    }

    private static McpClientTransport createStdioTransport(McpProperties.ServerConfig config) {
        String command = config.getCommand();
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Stdio 传输需要配置 'command'");
        }

        logger.info("创建 Stdio 传输: command={}, args={}", command, config.getArgs());

        ServerParameters params = ServerParameters.builder(command)
                .args(config.getArgs())
                .env(config.getEnv())
                .build();
        return new StdioClientTransport(params, McpJsonMapper.getDefault());
    }
}
