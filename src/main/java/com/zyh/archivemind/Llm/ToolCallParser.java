package com.zyh.archivemind.Llm;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用解析器
 * 负责将 LLM 返回的 tool_call 中的 arguments（JSON 字符串）解析为 Map
 */
@Component
public class ToolCallParser {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析工具调用参数
     *
     * @param toolCall 工具调用信息
     * @return 解析后的参数 Map
     */
    public Map<String, Object> parseArguments(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Collections.emptyMap();
        }

        try {
            String args = toolCall.getArguments().trim();
            if (args.isEmpty() || "{}".equals(args)) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(args, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            logger.error("解析工具调用参数失败: toolCall={}, error={}",
                    toolCall.getFunctionName(), e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 将工具执行结果序列化为 JSON 字符串
     *
     * @param result 工具执行结果
     * @return JSON 字符串
     */
    public String serializeResult(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("序列化工具结果失败: {}", e.getMessage(), e);
            return "{\"error\": \"序列化失败\"}";
        }
    }
}
