package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.Llm.UserLlmPreferenceService;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聊天处理服务
 * Phase 1 改造：接入 LlmRouter + 工具调用循环（Agent 模式）
 */
@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final int MAX_TOOL_ITERATIONS = 5;
    /** knowledge_search 工具定义，无状态，构建一次复用 */
    private static final List<ToolDefinition> TOOL_DEFINITIONS = buildToolDefinitions();

    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final ConversationSessionService conversationSessionService;
    private final LlmRouter llmRouter;
    private final ToolCallParser toolCallParser;
    private final UserLlmPreferenceService preferenceService;
    private final ObjectMapper objectMapper;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    /** 用于执行阻塞工具调用，避免占用 Reactor IO 线程 */
    private final ScheduledExecutorService toolExecutor =
            Executors.newScheduledThreadPool(4, r -> new Thread(r, "tool-executor"));

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       HybridSearchService searchService,
                       ConversationSessionService conversationSessionService,
                       LlmRouter llmRouter,
                       ToolCallParser toolCallParser,
                       UserLlmPreferenceService preferenceService) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.conversationSessionService = conversationSessionService;
        this.llmRouter = llmRouter;
        this.toolCallParser = toolCallParser;
        this.preferenceService = preferenceService;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, session.getId());
        try {
            String conversationId = getOrCreateConversationId(userId);
            responseBuilders.put(session.getId(), new StringBuilder());
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(session.getId(), responseFuture);

            List<Map<String, String>> history = getConversationHistory(conversationId);

            if (history.isEmpty()) {
                try {
                    conversationSessionService.autoGenerateTitle(conversationId, userMessage);
                } catch (Exception e) {
                    logger.warn("自动生成标题失败，会话ID: {}, 错误: {}", conversationId, e.getMessage());
                }
            }

            // 构建 LlmMessage 列表（system prompt + 历史 + 当前问题）
            List<LlmMessage> messages = buildLlmMessages(userMessage, history);

            // 构建工具定义
            List<ToolDefinition> tools = TOOL_DEFINITIONS;

            // 启动 Agent 循环
            executeAgentLoop(userId, userMessage, messages, tools, session, conversationId, responseFuture, 0);

        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            handleError(session, e);
            responseBuilders.remove(session.getId());
            CompletableFuture<String> future = responseFutures.remove(session.getId());
            if (future != null && !future.isDone()) future.completeExceptionally(e);
        }
    }

    /**
     * Agent 循环：调用 LLM → 判断响应类型 → 执行工具或输出文本
     */
    private void executeAgentLoop(String userId, String userMessage,
                                  List<LlmMessage> messages, List<ToolDefinition> tools,
                                  WebSocketSession session, String conversationId,
                                  CompletableFuture<String> responseFuture, int iteration) {
        if (iteration >= MAX_TOOL_ITERATIONS) {
            logger.warn("工具调用循环达到上限 {}，强制结束", MAX_TOOL_ITERATIONS);
            finishResponse(session, conversationId, userId, userMessage, responseFuture);
            return;
        }

        LlmProvider provider = preferenceService.getProviderForUser(userId);
        LlmRequest request = LlmRequest.builder()
                .messages(messages)
                .tools(provider.supportsToolCalling() ? tools : null)
                .build();

        // 用于标记本轮是否触发了工具调用（触发后由下一轮负责 onComplete 的收尾）
        final boolean[] toolCalled = {false};

        provider.streamChat(request, new LlmStreamCallback() {
            @Override
            public void onTextChunk(String chunk) {
                if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) return;
                StringBuilder builder = responseBuilders.get(session.getId());
                if (builder != null) builder.append(chunk);
                sendResponseChunk(session, chunk);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                toolCalled[0] = true;
                logger.info("LLM 请求调用工具: {}, 参数: {}", toolCall.getFunctionName(), toolCall.getArguments());
                sendToolCallNotification(session, toolCall, "executing");

                // 切换到独立线程串行执行，避免：
                // 1. 阻塞 Reactor IO 线程
                // 2. 多个工具调用并发修改 messages 列表
                CompletableFuture.runAsync(() -> {
                    String toolResult = executeToolCall(userId, toolCall);
                    sendToolCallNotification(session, toolCall, "done");
                    messages.add(LlmMessage.builder().role("assistant").toolCall(toolCall).build());
                    messages.add(LlmMessage.toolResult(toolCall.getId(), toolResult));
                    executeAgentLoop(userId, userMessage, messages, tools, session,
                            conversationId, responseFuture, iteration + 1);
                }, toolExecutor).exceptionally(ex -> {
                    logger.error("工具调用异步执行失败: {}", ex.getMessage(), ex);
                    handleError(session, ex);
                    responseFuture.completeExceptionally(ex);
                    return null;
                });
            }

            @Override
            public void onComplete() {
                // 只有没有触发工具调用时，才在这里收尾（文本直接回答的情况）
                if (!toolCalled[0]) {
                    finishResponse(session, conversationId, userId, userMessage, responseFuture);
                }
            }

            @Override
            public void onError(Throwable error) {
                handleError(session, error);
                responseFuture.completeExceptionally(error);
                responseBuilders.remove(session.getId());
                responseFutures.remove(session.getId());
            }
        });
    }

    /**
     * 执行工具调用（Phase 1 内置 knowledge_search）
     */
    private String executeToolCall(String userId, ToolCall toolCall) {
        try {
            Map<String, Object> args = toolCallParser.parseArguments(toolCall);

            if ("knowledge_search".equals(toolCall.getFunctionName())) {
                String query = (String) args.getOrDefault("query", "");
                logger.info("执行知识库搜索，query: {}", query);
                List<SearchResult> results = searchService.searchWithPermission(query, userId, 5);
                return toolCallParser.serializeResult(formatSearchResults(results));
            }

            logger.warn("未知工具: {}", toolCall.getFunctionName());
            return objectMapper.writeValueAsString(Map.of("error", "未知工具: " + toolCall.getFunctionName()));
        } catch (Exception e) {
            logger.error("工具执行失败: {}", e.getMessage(), e);
            try {
                return objectMapper.writeValueAsString(Map.of("error", "工具执行失败: " + e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\":\"工具执行失败\"}";
            }
        }
    }

    /**
     * 构建 LlmMessage 列表
     * system prompt 内嵌规则，历史消息转换为 LlmMessage，最后追加当前用户问题
     */
    private List<LlmMessage> buildLlmMessages(String userMessage, List<Map<String, String>> history) {
        List<LlmMessage> messages = new ArrayList<>();

        // system prompt：告知 LLM 可用工具及行为规则
        messages.add(LlmMessage.system(
                "你是ArchiveMind知识助手，须遵守：\n" +
                "1. 仅用简体中文作答。\n" +
                "2. 回答需先给结论，再给论据。\n" +
                "3. 如引用参考信息，请在句末加 (来源#编号: 文件名)。\n" +
                "4. 若无足够信息，请回答\"暂无相关信息\"并说明原因。\n" +
                "5. 你有一个工具 knowledge_search，可以搜索用户的知识库。" +
                "当问题需要查阅资料时，请主动调用该工具。"
        ));

        // 历史消息
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                messages.add(LlmMessage.user(content));
            } else if ("assistant".equals(role)) {
                messages.add(LlmMessage.assistant(content));
            }
        }

        // 当前用户问题
        messages.add(LlmMessage.user(userMessage));
        return messages;
    }

    /**
     * 构建工具定义列表（Phase 1 内置 knowledge_search）
     * 静态方法，结果缓存为常量复用
     */
    private static List<ToolDefinition> buildToolDefinitions() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "query", Map.of(
                        "type", "string",
                        "description", "搜索关键词或问题"
                )
        ));
        parameters.put("required", List.of("query"));

        return List.of(ToolDefinition.builder()
                .name("knowledge_search")
                .description("搜索用户的私有知识库，返回相关文档片段。当需要查阅资料、回答具体问题时调用。")
                .parameters(parameters)
                .build());
    }

    /**
     * 将搜索结果格式化为结构化列表
     */
    private List<Map<String, String>> formatSearchResults(List<SearchResult> results) {
        List<Map<String, String>> formatted = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String snippet = r.getTextContent();
            if (snippet.length() > 800) snippet = snippet.substring(0, 800) + "…";
            Map<String, String> item = new LinkedHashMap<>();
            item.put("index", String.valueOf(i + 1));
            item.put("file", r.getFileName() != null ? r.getFileName() : "unknown");
            item.put("content", snippet);
            formatted.add(item);
        }
        return formatted;
    }

    /**
     * 收尾：更新历史、发送完成通知、清理资源
     */
    private void finishResponse(WebSocketSession session, String conversationId,
                                String userId, String userMessage,
                                CompletableFuture<String> responseFuture) {
        StringBuilder builder = responseBuilders.remove(session.getId());
        responseFutures.remove(session.getId());

        String completeResponse = builder != null ? builder.toString() : "";
        if (!completeResponse.isEmpty()) {
            updateConversationHistory(conversationId, userMessage, completeResponse);
        }

        try {
            conversationSessionService.refreshSessionTTL(userId, conversationId);
        } catch (Exception e) {
            logger.warn("刷新会话TTL失败: {}", e.getMessage());
        }

        sendCompletionNotification(session);
        responseFuture.complete(completeResponse);
        logger.info("消息处理完成，用户ID: {}", userId);
    }

    // ── 以下为辅助方法，与原版保持一致 ──────────────────────────────────────

    private String getOrCreateConversationId(String userId) {
        String conversationId = conversationSessionService.getActiveSessionId(userId);
        if (conversationId == null) {
            SessionDTO newSession = conversationSessionService.createSession(userId);
            conversationId = newSession.getSessionId();
            logger.info("为用户 {} 自动创建新会话: {}", userId, conversationId);
        }
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (json == null) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("解析对话历史出错: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
        String key = "conversation:" + conversationId;
        List<Map<String, String>> history = getConversationHistory(conversationId);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", ts);
        history.add(userMsg);

        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", response);
        assistantMsg.put("timestamp", ts);
        history.add(assistantMsg);

        if (history.size() > 20) history = history.subList(history.size() - 20, history.size());

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (JsonProcessingException e) {
            logger.error("序列化对话历史出错: {}", e.getMessage(), e);
        }
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("chunk", chunk));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("发送响应块失败: {}", e.getMessage(), e);
        }
    }

    private void sendToolCallNotification(WebSocketSession session, ToolCall toolCall, String status) {
        try {
            Map<String, Object> notification = Map.of(
                    "type", "tool_call",
                    "function", toolCall.getFunctionName(),
                    "status", status
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("发送工具调用通知失败: {}", e.getMessage(), e);
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            Map<String, Object> notification = Map.of(
                    "type", "completion",
                    "status", "finished",
                    "message", "响应已完成",
                    "timestamp", System.currentTimeMillis()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("发送完成通知失败: {}", e.getMessage(), e);
        }
    }

    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("AI服务错误: {}", error.getMessage(), error);
        try {
            String json = objectMapper.writeValueAsString(Map.of("error", "AI服务暂时不可用，请稍后重试"));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        String sessionId = session.getId();
        stopFlags.put(sessionId, true);
        try {
            Map<String, Object> response = Map.of(
                    "type", "stop",
                    "message", "响应已停止",
                    "timestamp", System.currentTimeMillis()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            logger.error("发送停止确认失败: {}", e.getMessage(), e);
        }
        toolExecutor.schedule(() -> stopFlags.remove(sessionId), 2, TimeUnit.SECONDS);
    }
}
