package com.zyh.archivemind.Tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用信息
 * LLM 返回的工具调用指令
 */
public record ToolCall(String id, String functionName, String arguments) {

    private static final Logger logger = LoggerFactory.getLogger(ToolCall.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 LLM 返回的 arguments（JSON 字符串）为 Map
     */
    public Map<String, Object> parseArguments() {
        if (arguments == null) {
            return Collections.emptyMap();
        }

        try {
            String args = arguments.trim();
            if (args.isEmpty() || "{}".equals(args)) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(args, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.error("解析工具调用参数失败: functionName={}, arguments={}, error={}",
                    functionName, arguments, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 工具执行结果
     */
    public record ToolResult(boolean success, String content) {

        public static ToolResult success(String content) {
            return new ToolResult(true, content);
        }

        public static ToolResult failure(String errorMessage) {
            return new ToolResult(false, errorMessage);
        }
    }
}
