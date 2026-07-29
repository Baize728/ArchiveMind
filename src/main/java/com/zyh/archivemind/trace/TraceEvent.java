package com.zyh.archivemind.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单条 Trace 事件
 * 对应 trace_events_YYYY_MM 表的一行（选项 A：每事件一行）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceEvent {

    /** 事件类型 */
    public enum EventType {
        USER_INPUT,         // 用户输入
        LLM_CALL,           // 大模型调用
        TOOL_CALL,          // 工具调用
        AGENT_DURATION,     // Agent 总执行耗时（T1-1 替代 AGENT_START + AGENT_COMPLETE）
        INTENT_RECOGNIZED,  // 意图识别（T1-1）
        LEGACY,             // 历史废弃事件类型（AGENT_START/AGENT_COMPLETE 等）的兼容降级
        ERROR               // 出错
    }

    /** 阶段（对齐 Diet-Agent phase：INPUT/AGENT/LLM/TOOL/ERROR） */
    public enum Phase {
        INPUT, AGENT, LLM, TOOL, INTENT, ERROR
    }

    private String traceId;
    private String conversationId;
    private String userId;
    private String sessionId;

    /** 该事件在会话中的顺序（从 1 自增） */
    private int stepOrder;

    private EventType eventType;
    private Phase phase;

    /** LLM provider 标识（如 deepseek），工具事件为 null */
    private String model;

    private String inputPayload;
    private String outputPayload;

    private int inputTokens;
    private int outputTokens;
    private int totalTokens;

    private long latencyMs;
    private boolean success;

    private LocalDateTime createdAt;
}
