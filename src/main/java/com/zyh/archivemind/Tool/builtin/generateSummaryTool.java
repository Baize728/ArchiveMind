package com.zyh.archivemind.Tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class generateSummaryTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(generateSummaryTool.class);
    private static final int MAX_DOCS = 5;

    private final HybridSearchService searchService;
    private final UserLlmPreferenceService preferenceService;

    public generateSummaryTool(HybridSearchService searchService, UserLlmPreferenceService preferenceService) {
        this.searchService = searchService;
        this.preferenceService = preferenceService;
    }

    @Override
    public String getName() {
        return "generate_summary";
    }

    @Override
    public String getDescription() {
        return "对指定主题的知识库文档生成结构化摘要。适合用户要求整理、总结、归纳、提炼知识库内容时调用；本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。";
    }

    @Override
    public JsonNode getParameterSchema() {
        return Tool.buildSchema(
                new Param("topic", "string", "需要从知识库中整理和总结的主题", true),
                new Param("maxDocs", "integer", "用于生成摘要的最多相关片段数量，默认 5", false)
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        String topic = (String) params.get("topic");
        if (topic == null || topic.trim().isEmpty()) {
            return ToolResult.failure("摘要主题不能为空");
        }

        int maxDocs = MAX_DOCS;
        Object maxDocsObj = params.get("maxDocs");
        if (maxDocsObj instanceof Number) {
            maxDocs = ((Number) maxDocsObj).intValue();
            maxDocs = Math.max(1, Math.min(maxDocs, 20));
        }

        String userId = context.userId();

        // 1. 从知识库检索相关片段
        List<SearchResult> searchResults = searchService.searchWithPermission(topic, userId, maxDocs);

        if (searchResults.isEmpty()) {
            return ToolResult.success("未找到与 \"" + topic + "\" 相关的知识库文档，无法生成摘要。");
        }

        // 2. 构建检索片段上下文
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult r = searchResults.get(i);
            contextBuilder.append("[").append(i + 1).append("] ");
            if (r.getFileName() != null && !r.getFileName().isBlank()) {
                contextBuilder.append(r.getFileName()).append("\n");
            }
            String text = r.getTextContent();
            if (text != null && text.length() > 1200) {
                text = text.substring(0, 1200) + "...";
            }
            contextBuilder.append(text).append("\n\n");
        }

        // 3. 获取用户偏好的 LLM Provider
        LlmProvider provider = preferenceService.getProviderForUser(userId);

        // 4. 构建 LLM 请求
        String systemPrompt = "你是一个专业的知识整理助手。请根据提供的知识库片段，对主题进行结构化总结。"
                + "总结应包括：核心要点、关键信息、逻辑关系。"
                + "如果片段信息不完整，请说明'基于已有信息只能确认...'。"
                + "在总结中引用来源时请使用 [N] 编号。";

        String userMessage = "请总结主题：" + topic + "\n\n参考知识库片段：\n\n" + contextBuilder.toString();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.system(systemPrompt),
                        LlmMessage.user(userMessage)
                ))
                .params(GenerationParams.builder()
                        .temperature(0.2)
                        .maxTokens(1500)
                        .build())
                .build();

        // 5. 同步等待流式响应完成
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> summaryRef = new AtomicReference<>("");
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        try {
            provider.streamChat(request, new LlmStreamCallback() {
                @Override
                public void onTextChunk(String chunk) {
                    summaryRef.updateAndGet(s -> s + chunk);
                }

                @Override
                public void onToolCall(com.zyh.archivemind.Tool.ToolCall toolCall) {
                    // 内部 LLM 调用不应触发工具，忽略
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    logger.error("摘要生成失败: {}", error.getMessage(), error);
                    errorRef.set(error.getMessage());
                    latch.countDown();
                }
            });

            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("摘要生成被中断");
        }

        if (errorRef.get() != null) {
            return ToolResult.failure("摘要生成失败: " + errorRef.get());
        }

        String summary = summaryRef.get();
        if (summary.isBlank()) {
            return ToolResult.failure("摘要生成完成，但未返回有效内容。");
        }

        return ToolResult.success(summary);
    }

    /** 内部 LLM 调用可能较慢 */
    @Override
    public int getTimeoutSeconds() {
        return 60;
    }
}
