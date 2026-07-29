package com.zyh.archivemind.trace;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Trace 作用域，借鉴 Diet-Agent §8.9 的 TraceScope implements AutoCloseable。
 *
 * 与 Diet 的差异：Diet 用 ThreadLocal 持有（同步调用场景下成立）；
 * ArchiveMind 的 Agent 循环跨线程（Reactor -> toolExecutor），ThreadLocal 会失效，
 * 因此改为通过 AgentContext 显式透传同一个 TraceScope 引用（见 plan.md T0-1 方案）。
 *
 * 用法：
 * <pre>
 *   try (TraceScope scope = traceCollector.openTrace(...)) {
 *       scope.recordUserInput(...);
 *       // ... 业务执行，期间由 AgentExecutor 记录 LLM_CALL / TOOL_CALL ...
 *       scope.recordAgentComplete();
 *   } // close() 自动触发 collector.persist
 * </pre>
 */
public class TraceScope implements AutoCloseable {

    @Getter
    private final TraceContext context;
    private final TraceCollector collector;
    private final boolean noop;
    private boolean closed = false;

    TraceScope(TraceContext context, TraceCollector collector) {
        this.context = context;
        this.collector = collector;
        this.noop = false;
    }

    /** 空作用域：不做任何采集，close() 也是空操作（用于未开启 Trace 或采样跳过时） */
    private TraceScope() {
        this.context = null;
        this.collector = null;
        this.noop = true;
    }

    public static TraceScope noop() {
        return new TraceScope();
    }

    public String getTraceId() {
        return context != null ? context.getTraceId() : null;
    }

    public void recordUserInput(String input) {
        add(TraceEvent.EventType.USER_INPUT, TraceEvent.Phase.INPUT, null,
                input, null, 0, true);
    }

    public void recordAgentStart() {
        // T1-1: 已废弃，改用 recordAgentDuration 在完成时记录总耗时
        // 保留方法签名避免编译错误，但不再产生事件
    }

    public void recordLlmCall(String model, String inputPayload,
                               String outputPayload, long latencyMs) {
        add(TraceEvent.EventType.LLM_CALL, TraceEvent.Phase.LLM, model,
                inputPayload, outputPayload, latencyMs, true);
    }

    public void recordToolCall(String inputPayload, String outputPayload,
                               long latencyMs, boolean success) {
        add(TraceEvent.EventType.TOOL_CALL, TraceEvent.Phase.TOOL, null,
                inputPayload, outputPayload, latencyMs, success);
    }

    public void recordAgentComplete() {
        // T1-1: 已废弃，改用 recordAgentDuration 在完成时记录总耗时
        // 保留方法签名避免编译错误，但不再产生事件
    }

    /**
     * 记录 Agent 总执行耗时（T1-1 替代 AGENT_START + AGENT_COMPLETE 两个事件）。
     * 产生一条 AGENT_DURATION 事件，包含 latencyMs，无 payload。
     */
    public void recordAgentDuration(long latencyMs) {
        add(TraceEvent.EventType.AGENT_DURATION, TraceEvent.Phase.AGENT, null,
                null, null, latencyMs, true);
    }

    public void recordError(String message) {
        add(TraceEvent.EventType.ERROR, TraceEvent.Phase.ERROR, null,
                null, message, 0, false);
    }

    /** 记录意图识别结果（T1-1） */
    public void recordIntent(String userMessage, String intent, double confidence, String source) {
        String payload = String.format(
                "{\"intent\":\"%s\",\"confidence\":%.2f,\"source\":\"%s\"}",
                intent, confidence, source);
        add(TraceEvent.EventType.INTENT_RECOGNIZED, TraceEvent.Phase.INTENT, null,
                userMessage, payload, 0, true);
    }

    private void add(TraceEvent.EventType type, TraceEvent.Phase phase, String model,
                     String input, String output, long latencyMs, boolean success) {
        if (noop) {
            return;
        }
        int inTokens = estimateTokens(input);
        int outTokens = estimateTokens(output);
        TraceEvent event = TraceEvent.builder()
                .traceId(context.getTraceId())
                .conversationId(context.getConversationId())
                .userId(context.getUserId())
                .sessionId(context.getSessionId())
                .stepOrder(context.nextStepOrder())
                .eventType(type)
                .phase(phase)
                .model(model)
                .inputPayload(input)
                .outputPayload(output)
                .inputTokens(inTokens)
                .outputTokens(outTokens)
                .totalTokens(inTokens + outTokens)
                .latencyMs(latencyMs)
                .success(success)
                .createdAt(LocalDateTime.now())
                .build();
        context.addEvent(event);
    }

    /** token 估算：字符数 / 4（LLM 流式接口不返回真实用量，按 plan.md 约定估算） */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    @Override
    public void close() {
        if (noop || closed) {
            return;
        }
        closed = true;
        collector.persist(context);
    }
}
