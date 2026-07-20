package com.zyh.archivemind.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 客户端配置属性
 * 映射 application-mcp.yml 中的 mcp.* 配置
 */
@Component
@ConfigurationProperties(prefix = "mcp")
@Data
public class McpProperties {

    /** MCP 总开关，默认关闭 */
    private boolean enabled = false;

    /** MCP Server 配置，key 为 server 名称（用于生成工具名前缀） */
    private Map<String, ServerConfig> servers = new HashMap<>();

    @Data
    public static class ServerConfig {
        /** 传输类型："stdio" 或 "sse" */
        private String type = "stdio";

        /** stdio: 启动命令（如 npx、python、java） */
        private String command;

        /** stdio: 命令参数列表 */
        private List<String> args = new ArrayList<>();

        /** stdio: 进程环境变量 */
        private Map<String, String> env = new HashMap<>();

        /** sse: MCP Server SSE 端点 URL */
        private String url;

        /** 是否启用该 server */
        private boolean enabled = true;

        /** 初始化失败重试次数（0 = 不重试） */
        private int reconnectAttempts = 3;

        /** 重试间隔（秒） */
        private int reconnectDelaySeconds = 10;

        /** MCP 操作请求超时（秒） */
        private int requestTimeoutSeconds = 30;
    }
}
