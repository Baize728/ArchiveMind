package com.zyh.archivemind.agent;

import com.zyh.archivemind.Llm.LlmMessage;
import com.zyh.archivemind.Llm.LlmProvider;
import com.zyh.archivemind.Llm.LlmRequest;
import com.zyh.archivemind.Llm.LlmStreamCallback;
import com.zyh.archivemind.Tool.ToolCall;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.Tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 执行引擎
 * 实现 ReAct（Reasoning + Acting）循环
 *
 * 职责：
 * - 管理 Agent 循环（LLM 调用 → 工具执行 → 追加消息 → 再次调用 LLM）
 * - 通过 ToolRegistry 查找和执行 Tool
 * - 通过 AgentCallback 通知外部事件
 *
 * 不负责：
 * - WebSocket 通信（由 ChatHandler 通过 AgentCallback 桥接）
 * - LLM Provider 选择（由调用方传入）
 * - 会话管理（由 ChatHandler 负责）
 */
@Component
public class AgentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);

    private final ToolRegistry toolRegistry;

    /** 用于执行阻塞的 Tool 调用，避免占用 Reactor IO 线程 */
    private final ScheduledExecutorService toolExecutor =
            Executors.newScheduledThreadPool(4, r -> new Thread(r, "agent-tool-executor"));

    public AgentExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行 Agent
     *
     * @param provider LLM Provider（由调用方根据用户偏好选择）
     * @param config   Agent 配置
     * @param context  Agent 执行上下文（包含初始消息列表）
     * @param callback 事件回调
     */
    public void execute(LlmProvider provider, AgentConfig config,
                        AgentContext context, AgentCallback callback) {
        logger.info("开始执行 Agent，最大循环: {}", config.getMaxIterations());
        try {
            List<Tool> tools = toolRegistry.getAll();
            executeLoop(provider, config, context, tools, callback);
        } catch (Exception e) {
            logger.error("Agent 执行失败: {}", e.getMessage(), e);
            callback.onError(e);
        }
    }

    /**
     * Agent 循环核心逻辑
     * 递归 + CompletableFuture.runAsync 切线程，避免栈溢出和阻塞 Reactor 线程
     */
    private void executeLoop(LlmProvider provider, AgentConfig config,
                             AgentContext context, List<Tool> tools,
                             AgentCallback callback) {
        if (context.getCurrentIteration() >= config.getMaxIterations()) {
            logger.warn("Agent 达到最大循环次数: {}", config.getMaxIterations());
            callback.onComplete();
            return;
        }

        LlmRequest request = LlmRequest.builder()
                .messages(context.getMessages())
                .tools(provider.supportsToolCalling() && !tools.isEmpty() ? tools : null)
                .build();

        final boolean[] toolCalled = {false};

        provider.streamChat(request, new LlmStreamCallback() {
            @Override
            public void onThinkingChunk(String chunk) {
                callback.onThinkingChunk(chunk);
            }

            @Override
            public void onTextChunk(String chunk) {
                callback.onTextChunk(chunk);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                toolCalled[0] = true;
                logger.info("Agent 请求调用工具: {}, 参数: {}",
                        toolCall.functionName(), toolCall.arguments());
                callback.onToolCallStart(toolCall);

                // 切到独立线程执行 Tool，避免阻塞 Reactor IO 线程
                CompletableFuture.runAsync(() -> {
                    Tool.ToolResult result = executeTool(toolCall, context);
                    callback.onToolCallEnd(toolCall, result);

                    // 追加 assistant tool_call 消息和 tool result 消息
                    context.getMessages().add(LlmMessage.builder()
                            .role("assistant").toolCall(toolCall).build());
                    context.getMessages().add(LlmMessage.toolResult(
                            toolCall.id(), result.content()));

                    context.setCurrentIteration(context.getCurrentIteration() + 1);

                    // 继续下一轮循环
                    executeLoop(provider, config, context, tools, callback);
                }, toolExecutor).exceptionally(ex -> {
                    logger.error("工具调用异步执行失败: {}", ex.getMessage(), ex);
                    callback.onError(ex);
                    return null;
                });
            }

            @Override
            public void onComplete() {
                if (!toolCalled[0]) {
                    callback.onComplete();
                }
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 执行 工具，带超时控制
     * 注意：此方法已在 toolExecutor 线程上运行，直接同步执行 Tool
     * 超时通过 watchdog 线程实现，避免提交到同一线程池导致死锁
     */
    private Tool.ToolResult executeTool(ToolCall toolCall, AgentContext context) {
        Tool tool = toolRegistry.get(toolCall.functionName());
        if (tool == null) {
            logger.warn("未找到 Tool: {}", toolCall.functionName());
            return Tool.ToolResult.failure("未知工具: " + toolCall.functionName());
        }

        try {
            Map<String, Object> params = toolCall.parseArguments();
            long startTime = System.currentTimeMillis();

            // 直接在当前线程同步执行（当前已在 toolExecutor 线程上）
            Tool.ToolResult result = tool.execute(context.getToolContext(), params);

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Tool {} 执行完成，耗时: {}ms, 成功: {}",
                    tool.getName(), elapsed, result.success());
            return result;
        } catch (Exception e) {
            logger.error("Tool {} 执行异常: {}", tool.getName(), e.getMessage(), e);
            return Tool.ToolResult.failure("工具执行失败: " + e.getMessage());
        }
    }
}
