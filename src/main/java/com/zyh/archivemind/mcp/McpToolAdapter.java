package com.zyh.archivemind.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zyh.archivemind.Tool.Tool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * MCP Tool → ArchiveMind Tool 适配器
 * <p>
 * 将 MCP Server 提供的工具包装为 ArchiveMind 的 Tool 接口，
 * 使得 AgentExecutor 可以透明地调用 MCP 工具。
 * </p>
 */
public class McpToolAdapter implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String archiveMindName;      // "mcp.filesystem.read_file"
    private final String originalToolName;      // "read_file"
    private final String description;
    private final JsonNode parameterSchema;
    private final McpSyncClient client;
    private final int requestTimeoutSeconds;

    /**
     * @param serverName     MCP Server 名称（如 "filesystem"）
     * @param tool           MCP 工具对象
     * @param client         对应的 McpSyncClient
     * @param timeoutSeconds 请求超时秒数
     */
    public McpToolAdapter(String serverName,
                          McpSchema.Tool tool,
                          McpSyncClient client,
                          int timeoutSeconds) {
        this.originalToolName = tool.name();
        this.archiveMindName = "mcp." + serverName + "." + tool.name();
        this.description = tool.description() + " [来自 MCP Server: " + serverName + "]";
        this.parameterSchema = convertJsonSchema(tool.inputSchema());
        this.client = client;
        this.requestTimeoutSeconds = timeoutSeconds;
    }

    @Override
    public String getName() {
        return archiveMindName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public JsonNode getParameterSchema() {
        return parameterSchema;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            logger.info("MCP 工具调用: {} (原始: {})", archiveMindName, originalToolName);
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(originalToolName, params);
            McpSchema.CallToolResult mcpResult = client.callTool(request);

            // 提取文本内容
            StringBuilder sb = new StringBuilder();
            if (mcpResult.content() != null) {
                for (McpSchema.Content content : mcpResult.content()) {
                    if (content instanceof McpSchema.TextContent textContent) {
                        sb.append(textContent.text());
                    }
                }
            }

            String text = sb.toString();
            if (mcpResult.isError() != null && mcpResult.isError()) {
                logger.warn("MCP 工具 '{}' 返回错误: {}", archiveMindName, text);
                return ToolResult.failure(text.isEmpty() ? "MCP 工具返回错误" : text);
            }
            return ToolResult.success(text);
        } catch (Exception e) {
            logger.error("MCP 工具 '{}' 执行失败: {}", archiveMindName, e.getMessage(), e);
            return ToolResult.failure("MCP工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public int getTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /**
     * 将 MCP 的 JsonSchema 转换为 Jackson JsonNode
     */
    private static JsonNode convertJsonSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            ObjectNode empty = MAPPER.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", schema.type() != null ? schema.type() : "object");
        if (schema.properties() != null) {
            node.set("properties", MAPPER.valueToTree(schema.properties()));
        }
        if (schema.required() != null && !schema.required().isEmpty()) {
            node.set("required", MAPPER.valueToTree(schema.required()));
        }
        if (schema.additionalProperties() != null) {
            node.put("additionalProperties", schema.additionalProperties());
        }
        return node;
    }
}
