package com.zyh.archivemind.Llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 消息体
 * 支持普通文本消息和工具调用消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {
    /** 角色：system / user / assistant / tool */
    private String role;

    /** 文本内容（普通消息时使用） */
    private String content;

    /** 工具调用信息（assistant 角色返回工具调用时使用） */
    private ToolCall toolCall;

    /** 工具调用ID（tool 角色返回工具结果时使用，关联到对应的 toolCall） */
    private String toolCallId;

    /**
     * 创建 system 消息
     */
    public static LlmMessage system(String content) {
        return LlmMessage.builder().role("system").content(content).build();
    }

    /**
     * 创建 user 消息
     */
    public static LlmMessage user(String content) {
        return LlmMessage.builder().role("user").content(content).build();
    }

    /**
     * 创建 assistant 消息
     */
    public static LlmMessage assistant(String content) {
        return LlmMessage.builder().role("assistant").content(content).build();
    }

    /**
     * 创建 tool 结果消息
     */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return LlmMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .content(content)
                .build();
    }
}
