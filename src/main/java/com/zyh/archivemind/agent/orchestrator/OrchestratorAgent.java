package com.zyh.archivemind.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.agent.*;
import com.zyh.archivemind.skill.SkillContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 编排 Agent
 * 分析任务复杂度，将复杂任务分解为子任务，按依赖关系调度执行，合并结果
 */
@Component
public class OrchestratorAgent {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorAgent.class);

    private final AgentExecutor agentExecutor;
    private final LlmRouter llmRouter;
    private final ObjectMapper objectMapper;

    private final ExecutorService sharedExecutor =
            Executors.newFixedThreadPool(3, r -> new Thread(r, "orchestrator-worker"));

    public OrchestratorAgent(AgentExecutor agentExecutor, LlmRouter llmRouter,
                             ObjectMapper objectMapper) {
        this.agentExecutor = agentExecutor;
        this.llmRouter = llmRouter;
        this.objectMapper = objectMapper;
    }

    public void orchestrate(String userMessage, SkillContext skillContext,
                            AgentCallback callback) {
        try {
            TaskPlan plan = analyzeAndPlan(userMessage);

            if (plan.getSubTasks() == null || plan.getSubTasks().size() <= 1) {
                logger.info("简单任务，使用默认 Agent 处理");
                executeWithDefaultAgent(userMessage, skillContext, callback);
                return;
            }

            callback.onThinkingChunk("任务已分解为 " + plan.getSubTasks().size() + " 个子任务");
            List<SubTaskResult> results = executeSubTasks(plan, skillContext, callback);
            mergeResults(results, userMessage, callback);
        } catch (Exception e) {
            logger.error("编排执行失败: {}", e.getMessage(), e);
            callback.onError(e);
        }
    }

    private TaskPlan analyzeAndPlan(String userMessage) throws Exception {
        String planPrompt = """
                分析以下用户任务，判断是否需要分解为多个子任务。
                如果需要分解，请返回 JSON 格式的执行计划：
                {"needsDecomposition": true, "subTasks": [{"id": 1, "description": "子任务描述", "agentType": "research", "dependsOn": []}]}
                如果不需要分解，返回：{"needsDecomposition": false, "subTasks": []}
                只输出 JSON，不要输出其他内容。
                
                用户任务：""" + userMessage;

        List<LlmMessage> messages = List.of(
                LlmMessage.system("你是一个任务规划专家，负责分析和分解复杂任务。"),
                LlmMessage.user(planPrompt)
        );

        LlmRequest request = LlmRequest.builder().messages(messages).build();

        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();

        llmRouter.getDefaultProvider().streamChat(request, new LlmStreamCallback() {
            @Override
            public void onTextChunk(String chunk) { text.append(chunk); }
            @Override
            public void onToolCall(ToolCall toolCall) {}
            @Override
            public void onComplete() { future.complete(text.toString()); }
            @Override
            public void onError(Throwable error) { future.completeExceptionally(error); }
        });

        String planJson = future.get(30, TimeUnit.SECONDS);
        return parsePlan(planJson);
    }

    private TaskPlan parsePlan(String json) {
        try {
            // 尝试提取 JSON 部分（LLM 可能返回 markdown 包裹的 JSON）
            String trimmed = json.trim();
            if (trimmed.startsWith("```")) {
                int start = trimmed.indexOf("{");
                int end = trimmed.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    trimmed = trimmed.substring(start, end + 1);
                }
            }
            return objectMapper.readValue(trimmed, TaskPlan.class);
        } catch (Exception e) {
            logger.warn("解析 TaskPlan JSON 失败，回退为单任务: {}", e.getMessage());
            return TaskPlan.singleTask();
        }
    }

    /**
     * 拓扑排序：按依赖关系将子任务分层
     */
    List<List<TaskPlan.SubTask>> topologicalSort(List<TaskPlan.SubTask> subTasks) {
        Map<Integer, TaskPlan.SubTask> taskMap = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> dependents = new HashMap<>();

        for (TaskPlan.SubTask task : subTasks) {
            taskMap.put(task.getId(), task);
            inDegree.put(task.getId(), task.getDependsOn() != null ? task.getDependsOn().size() : 0);
            dependents.put(task.getId(), new ArrayList<>());
        }

        for (TaskPlan.SubTask task : subTasks) {
            if (task.getDependsOn() != null) {
                for (int depId : task.getDependsOn()) {
                    dependents.computeIfAbsent(depId, k -> new ArrayList<>()).add(task.getId());
                }
            }
        }

        List<List<TaskPlan.SubTask>> layers = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();

        while (processed.size() < subTasks.size()) {
            List<TaskPlan.SubTask> layer = new ArrayList<>();
            for (TaskPlan.SubTask task : subTasks) {
                if (!processed.contains(task.getId()) && inDegree.getOrDefault(task.getId(), 0) == 0) {
                    layer.add(task);
                }
            }
            if (layer.isEmpty()) break; // 防止死循环（有环的情况）

            for (TaskPlan.SubTask task : layer) {
                processed.add(task.getId());
                for (int depId : dependents.getOrDefault(task.getId(), List.of())) {
                    inDegree.merge(depId, -1, Integer::sum);
                }
            }
            layers.add(layer);
        }
        return layers;
    }

    private List<SubTaskResult> executeSubTasks(TaskPlan plan, SkillContext skillContext,
                                                 AgentCallback callback) throws Exception {
        List<SubTaskResult> results = new ArrayList<>();
        Map<Integer, SubTaskResult> resultMap = new ConcurrentHashMap<>();

        List<List<TaskPlan.SubTask>> layers = topologicalSort(plan.getSubTasks());

        for (List<TaskPlan.SubTask> layer : layers) {
            List<Future<SubTaskResult>> futures = new ArrayList<>();
            for (TaskPlan.SubTask subTask : layer) {
                futures.add(sharedExecutor.submit(() -> {
                    callback.onThinkingChunk("正在执行: " + subTask.getDescription());
                    String enriched = enrichWithDependencies(subTask, resultMap);
                    return executeSingleSubTask(enriched, subTask, skillContext);
                }));
            }
            for (Future<SubTaskResult> f : futures) {
                SubTaskResult result = f.get(120, TimeUnit.SECONDS);
                resultMap.put(result.getId(), result);
                results.add(result);
            }
        }
        return results;
    }

    private String enrichWithDependencies(TaskPlan.SubTask subTask,
                                           Map<Integer, SubTaskResult> resultMap) {
        StringBuilder desc = new StringBuilder(subTask.getDescription());
        if (subTask.getDependsOn() != null && !subTask.getDependsOn().isEmpty()) {
            desc.append("\n\n参考信息：");
            for (int depId : subTask.getDependsOn()) {
                SubTaskResult dep = resultMap.get(depId);
                if (dep != null) {
                    desc.append("\n- [").append(dep.getDescription()).append("]: ")
                            .append(dep.getResult());
                }
            }
        }
        return desc.toString();
    }

    private SubTaskResult executeSingleSubTask(String description, TaskPlan.SubTask subTask,
                                                SkillContext skillContext) throws Exception {
        LlmProvider provider = llmRouter.getDefaultProvider();
        AgentConfig config = AgentConfig.builder()
                .agentId(subTask.getAgentType())
                .name(subTask.getAgentType() + "-agent")
                .maxIterations(3)
                .build();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.user(description));

        AgentContext agentContext = AgentContext.builder()
                .skillContext(skillContext)
                .messages(messages)
                .build();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        StringBuilder textBuilder = new StringBuilder();

        agentExecutor.execute(provider, config, agentContext, new AgentCallback() {
            @Override
            public void onThinkingChunk(String chunk) {}
            @Override
            public void onTextChunk(String chunk) { textBuilder.append(chunk); }
            @Override
            public void onToolCallStart(ToolCall tc) {}
            @Override
            public void onToolCallEnd(ToolCall tc, com.zyh.archivemind.skill.SkillResult r) {}
            @Override
            public void onComplete() { resultFuture.complete(textBuilder.toString()); }
            @Override
            public void onError(Throwable err) { resultFuture.completeExceptionally(err); }
        });

        String result = resultFuture.get(60, TimeUnit.SECONDS);
        return SubTaskResult.builder()
                .id(subTask.getId())
                .description(subTask.getDescription())
                .result(result)
                .build();
    }

    private void mergeResults(List<SubTaskResult> results, String originalMessage,
                               AgentCallback callback) {
        StringBuilder mergePrompt = new StringBuilder();
        mergePrompt.append("用户原始问题：").append(originalMessage).append("\n\n");
        mergePrompt.append("以下是各个子任务的执行结果，请综合所有结果生成最终回答：\n\n");
        for (SubTaskResult result : results) {
            mergePrompt.append("### 子任务: ").append(result.getDescription()).append("\n");
            mergePrompt.append(result.getResult()).append("\n\n");
        }

        List<LlmMessage> messages = List.of(
                LlmMessage.system("你是一个信息整合专家，负责将多个子任务的结果合并为一个完整、连贯的回答。"),
                LlmMessage.user(mergePrompt.toString())
        );

        LlmRequest request = LlmRequest.builder().messages(messages).build();

        llmRouter.getDefaultProvider().streamChat(request, new LlmStreamCallback() {
            @Override
            public void onTextChunk(String chunk) { callback.onTextChunk(chunk); }
            @Override
            public void onToolCall(ToolCall toolCall) {}
            @Override
            public void onComplete() { callback.onComplete(); }
            @Override
            public void onError(Throwable error) { callback.onError(error); }
        });
    }

    private void executeWithDefaultAgent(String userMessage, SkillContext skillContext,
                                          AgentCallback callback) {
        LlmProvider provider = llmRouter.getDefaultProvider();
        AgentConfig config = AgentConfig.builder()
                .name("default")
                .maxIterations(5)
                .build();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system("你是ArchiveMind知识助手。当问题需要查阅资料时，请主动调用可用的工具。"));
        messages.add(LlmMessage.user(userMessage));

        AgentContext context = AgentContext.builder()
                .skillContext(skillContext)
                .messages(messages)
                .build();

        agentExecutor.execute(provider, config, context, callback);
    }

    @PreDestroy
    public void shutdown() {
        sharedExecutor.shutdown();
    }
}
