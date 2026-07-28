package com.zyh.archivemind.trace;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次会话的 Trace 数据载体。
 * 跨线程安全（Agent 循环在 Reactor 线程与 toolExecutor 线程间切换），
 * 因此用 CopyOnWriteArrayList + AtomicInteger 显式共享同一引用（见 AgentContext）。
 */
@Data
public class TraceContext {

    private final String traceId;
    private final String conversationId;
    private final String userId;
    private final String sessionId;

    /** 在线落库开关 */
    private final boolean onlinePersist;
    /** 评测模式开关 */
    private final boolean evalMode;

    private final AtomicInteger stepOrder = new AtomicInteger(0);
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    public TraceContext(String traceId, String conversationId, String userId,
                         String sessionId, boolean onlinePersist, boolean evalMode) {
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.onlinePersist = onlinePersist;
        this.evalMode = evalMode;
    }

    /** 自增并返回下一个 stepOrder（从 1 开始） */
    public int nextStepOrder() {
        return stepOrder.incrementAndGet();
    }

    public void addEvent(TraceEvent event) {
        events.add(event);
    }
}
