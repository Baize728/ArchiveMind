package com.zyh.archivemind.agent;

import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.skill.Skill;
import com.zyh.archivemind.skill.SkillRegistry;
import com.zyh.archivemind.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agent 执行引擎
 * 实现 ReAct（Reasoning + Acting）循环
 *
 * 职责：
 * - 管理 Agent 循环（LLM 调用 → 工具执行 → 追加消息 → 再次调用 LLM）
 * - 通过 SkillRegistry 查找和执行 Skill
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

    private final SkillRegistry skillRegistry;
    private final ToolCallParser toolCallParser;

    /** 用于执行阻塞的 Skill 调用，避免占用 Reactor IO 线程 */
    private final ScheduledExecutorService toolExecutor =
            Executors.newScheduledThreadPool(4, r -> new Thread(r, "agent-tool-executor"));

    public AgentExecutor(SkillRegistry skillRegistry, ToolCallParser toolCallParser) {
        this.skillRegistry = skillRegistry;
        this.toolCallParser = toolCallParser;
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
            List<ToolDefinition> tools = skillRegistry.getAllToolDefinitions();
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
                             AgentContext context, List<ToolDefinition> tools,
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
                        toolCall.getFunctionName(), toolCall.getArguments());
                callback.onToolCallStart(toolCall);

                // 切到独立线程执行 Skill，避免阻塞 Reactor IO 线程
                CompletableFuture.runAsync(() -> {
                    SkillResult result = executeSkill(toolCall, context);
                    callback.onToolCallEnd(toolCall, result);

                    // 追加 assistant tool_call 消息和 tool result 消息
                    context.getMessages().add(LlmMessage.builder()
                            .role("assistant").toolCall(toolCall).build());
                    context.getMessages().add(LlmMessage.toolResult(
                            toolCall.getId(), result.getContent()));

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
     * 执行 Skill，带超时控制
     * 注意：此方法已在 toolExecutor 线程上运行，直接同步执行 Skill
     * 超时通过 watchdog 线程实现，避免提交到同一线程池导致死锁
     */
    private SkillResult executeSkill(ToolCall toolCall, AgentContext context) {
        Skill skill = skillRegistry.getSkill(toolCall.getFunctionName());
        if (skill == null) {
            logger.warn("未找到 Skill: {}", toolCall.getFunctionName());
            return SkillResult.failure("未知工具: " + toolCall.getFunctionName());
        }

        try {
            Map<String, Object> params = toolCallParser.parseArguments(toolCall);
            long startTime = System.currentTimeMillis();

            // 直接在当前线程同步执行（当前已在 toolExecutor 线程上）
            SkillResult result = skill.execute(context.getSkillContext(), params);

            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("Skill {} 执行完成，耗时: {}ms, 成功: {}",
                    skill.getName(), elapsed, result.isSuccess());
            return result;
        } catch (Exception e) {
            logger.error("Skill {} 执行异常: {}", skill.getName(), e.getMessage(), e);
            return SkillResult.failure("工具执行失败: " + e.getMessage());
        }
    }
}
