package com.zyh.archivemind.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端连接
 * 管理与单个外部 MCP Server 的 JSON-RPC 连接（仅 stdio 模式）
 */
public class McpClientConnection {

    private static final Logger logger = LoggerFactory.getLogger(McpClientConnection.class);

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    private Process process;
    private PrintWriter writer;
    private BufferedReader reader;
    private List<ToolDefinition> cachedTools;
    private boolean connected = false;

    public McpClientConnection(McpServerConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 建立连接：启动进程 → initialize → notifications/initialized → tools/list
     */
    public void connect() throws Exception {
        String type = config.getTransportType();
        if ("stdio".equals(type)) {
            connectStdio();
        } else if ("sse".equals(type)) {
            connectSse();
        } else {
            throw new IllegalArgumentException("不支持的传输类型: " + type);
        }
        sendInitialize();
        fetchToolList();
        this.connected = true;
        logger.info("MCP Client 连接成功: serverId={}, tools={}", config.getServerId(),
                cachedTools != null ? cachedTools.size() : 0);
    }

    private void connectStdio() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null && !config.getArgs().isBlank()) {
            List<String> args = objectMapper.readValue(config.getArgs(),
                    new TypeReference<List<String>>() {});
            command.addAll(args);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        if (config.getEnvVars() != null && !config.getEnvVars().isBlank()) {
            Map<String, String> envVars = objectMapper.readValue(config.getEnvVars(),
                    new TypeReference<Map<String, String>>() {});
            pb.environment().putAll(envVars);
        }
        pb.redirectErrorStream(false);

        this.process = pb.start();
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        this.writer = new PrintWriter(process.getOutputStream(), true);
        logger.info("stdio 进程已启动: serverId={}, command={}", config.getServerId(), command);
    }

    private void connectSse() {
        throw new UnsupportedOperationException(
                "SSE 传输模式暂未实现，请使用 stdio 模式。serverId=" + config.getServerId());
    }

    private void sendInitialize() throws Exception {
        Map<String, Object> params = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "archivemind-agent", "version", "1.0.0")
        );
        sendRequest("initialize", params);
        sendNotification("notifications/initialized", null);
    }

    /**
     * 发送 JSON-RPC 请求（带 id，等待响应）
     */
    private JsonNode sendRequest(String method, Map<String, Object> params) throws Exception {
        int id = requestIdCounter.getAndIncrement();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }

        String json = objectMapper.writeValueAsString(request);
        logger.debug("发送 MCP 请求: {}", json);
        writer.println(json);
        writer.flush();

        String responseLine = reader.readLine();
        if (responseLine == null) {
            throw new IOException("MCP Server 无响应: serverId=" + config.getServerId());
        }
        logger.debug("收到 MCP 响应: {}", responseLine);

        JsonNode response = objectMapper.readTree(responseLine);
        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            throw new RuntimeException("MCP 错误 [" + config.getServerId() + "]: "
                    + error.path("message").asText());
        }
        return response.path("result");
    }

    /**
     * 发送 JSON-RPC 通知（无 id，不等待响应）
     */
    private void sendNotification(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", params);
        }
        String json = objectMapper.writeValueAsString(notification);
        writer.println(json);
        writer.flush();
    }

    private void fetchToolList() throws Exception {
        JsonNode result = sendRequest("tools/list", null);
        JsonNode tools = result.path("tools");
        this.cachedTools = new ArrayList<>();
        for (JsonNode tool : tools) {
            cachedTools.add(ToolDefinition.builder()
                    .name(tool.path("name").asText())
                    .description(tool.path("description").asText())
                    .parameters(objectMapper.convertValue(tool.path("inputSchema"), Map.class))
                    .build());
        }
    }

    /**
     * 调用 MCP 工具，返回结果文本
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        JsonNode result = sendRequest("tools/call",
                Map.of("name", toolName, "arguments", arguments));
        JsonNode content = result.path("content");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                text.append(item.path("text").asText());
            }
        }
        return text.toString();
    }

    public List<ToolDefinition> getTools() {
        return cachedTools != null ? cachedTools : Collections.emptyList();
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        if (writer != null) {
            try { writer.close(); } catch (Exception ignored) {}
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
        }
        if (process != null) {
            process.destroy();
            logger.info("MCP stdio 进程已关闭: serverId={}", config.getServerId());
        }
        connected = false;
    }
}
