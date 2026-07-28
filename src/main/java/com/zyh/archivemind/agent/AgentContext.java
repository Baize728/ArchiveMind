package com.zyh.archivemind.agent;

import com.zyh.archivemind.Llm.LlmMessage;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.trace.TraceScope;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行上下文
 * 维护整个 Agent 执行过程中的状态
 */
@Data
@Builder
public class AgentContext {

    /** 工具 执行上下文 */
    private Tool.ToolContext toolContext;

    /** 消息列表（随 Agent 循环不断追加） */
    @Builder.Default
    private List<LlmMessage> messages = new ArrayList<>();

    /** 当前循环次数 */
    @Builder.Default
    private int currentIteration = 0;

    /**
     * 本次会话的 Trace 作用域（在线/评测采集用，可空）。
     * 通过 AgentContext 显式透传（Agent 循环跨 Reactor / toolExecutor 线程，
     * 不能用 ThreadLocal），保证所有线程写入同一份 Trace。
     */
    private TraceScope traceScope;
}
