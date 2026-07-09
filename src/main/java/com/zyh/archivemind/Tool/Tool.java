package com.zyh.archivemind.Tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 工具统一接口
 * 所有可被 Agent 调用的工具都必须实现此接口
 * 实现类使用 @Component 注解，由 Spring 自动扫描注册到 ToolRegistry
 */
public interface Tool {

    ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 参数定义，覆盖简单场景；复杂场景直接返回自定义 JsonNode
     */
    record Param(String name, String type, String description, boolean required) {
        public Param(String name, String type, String description) {
            this(name, type, description, true);
        }
    }

    /**
     * 工具执行上下文，包含当前用户、会话等信息
     */
    record ToolContext(String userId, String sessionId, String conversationId) {}

    /** 工具唯一名称 */
    String getName();

    /** 工具描述（LLM 根据此描述决定是否调用） */
    String getDescription();

    /** 参数 JSON Schema */
    JsonNode getParameterSchema();

    /**
     * 执行工具
     * @param context 执行上下文（包含用户信息、会话信息等）
     * @param params  调用参数（由 LLM 生成）
     * @return 执行结果
     */
    ToolCall.ToolResult execute(ToolContext context, Map<String, Object> params);

    /** 执行超时秒数，默认 30 秒 */
    default int getTimeoutSeconds() {
        return 30;
    }

    /** 从 Param 列表自动生成 JSON Schema */
    static JsonNode buildSchema(Param... params) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Param p : params) {
            properties.putObject(p.name())
                    .put("type", p.type())
                    .put("description", p.description());
            if (p.required()) required.add(p.name());
        }
        return schema;
    }
}
