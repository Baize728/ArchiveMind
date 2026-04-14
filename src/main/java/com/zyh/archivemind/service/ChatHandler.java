package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.Llm.UserLlmPreferenceService;
import com.zyh.archivemind.agent.AgentCallback;
import com.zyh.archivemind.agent.AgentConfig;
import com.zyh.archivemind.agent.AgentContext;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.skill.SkillContext;
import com.zyh.archivemind.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 聊天处理服务
 * Phase 2 改造：Agent 循环委托给 AgentExecutor，ChatHandler 只负责 WebSocket 通信 + 会话管理
 */
@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ConversationSessionService conversationSessionService;
    private final UserLlmPreferenceService preferenceService;
    private final AgentExecutor agentExecutor;
    private final com.zyh.archivemind.agent.orchestrator.OrchestratorAgent orchestratorAgent;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> thinkingBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    /** 用于延迟清理 stopFlag */
    private final java.util.concurrent.ScheduledExecutorService toolCleanupExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "stop-flag-cleanup"));

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       ConversationSessionService conversationSessionService,
                       UserLlmPreferenceService preferenceService,
                       AgentExecutor agentExecutor,
                       com.zyh.archivemind.agent.orchestrator.OrchestratorAgent orchestratorAgent,
                       AiProperties aiProperties) {
        this.redisTemplate = redisTemplate;
        this.conversationSessionService = conversationSessionService;
        this.preferenceService = preferenceService;
        this.agentExecutor = agentExecutor;
        this.orchestratorAgent = orchestratorAgent;
        this.aiProperties = aiProperties;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, session.getId());
        try {
            String conversationId = getOrCreateConversationId(userId);
            responseBuilders.put(session.getId(), new StringBuilder());
            thinkingBuilders.put(session.getId(), new StringBuilder());
            sessionStartTimes.put(session.getId(), System.currentTimeMillis());
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

            // 获取用户偏好的 LLM Provider
            LlmProvider provider = preferenceService.getProviderForUser(userId);

            // 构建 Agent 配置
            AgentConfig config = AgentConfig.builder()
                    .maxIterations(5)
                    .build();

            // 构建 Agent 上下文
            SkillContext skillContext = SkillContext.builder()
                    .userId(userId)
                    .sessionId(session.getId())
                    .conversationId(conversationId)
                    .build();

            AgentContext agentContext = AgentContext.builder()
                    .skillContext(skillContext)
                    .messages(messages)
                    .build();

            // 执行 Agent，通过回调桥接 WebSocket
            AgentCallback agentCallback = new AgentCallback() {
                @Override
                public void onThinkingChunk(String chunk) {
                    if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) return;
                    StringBuilder builder = thinkingBuilders.get(session.getId());
                    if (builder != null) builder.append(chunk);
                    sendThinkingChunk(session, chunk);
                }

                @Override
                public void onTextChunk(String chunk) {
                    if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) return;
                    StringBuilder builder = responseBuilders.get(session.getId());
                    if (builder != null) builder.append(chunk);
                    sendAnswerChunk(session, chunk);
                }

                @Override
                public void onToolCallStart(ToolCall toolCall) {
                    sendToolCallNotification(session, toolCall, "executing");
                }

                @Override
                public void onToolCallEnd(ToolCall toolCall, SkillResult result) {
                    sendToolCallNotification(session, toolCall,
                            result.isSuccess() ? "done" : "failed");
                }

                @Override
                public void onComplete() {
                    finishResponse(session, conversationId, userId, userMessage, responseFuture);
                }

                @Override
                public void onError(Throwable error) {
                    handleError(session, error);
                    responseFuture.completeExceptionally(error);
                    cleanupSession(session.getId());
                    responseFutures.remove(session.getId());
                }
            };

            // 根据配置开关路由：编排模式 or 直接执行
            if (aiProperties.getOrchestrator().isEnabled()) {
                orchestratorAgent.orchestrate(userMessage, skillContext, agentCallback);
            } else {
                agentExecutor.execute(provider, config, agentContext, agentCallback);
            }

        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            handleError(session, e);
            cleanupSession(session.getId());
            CompletableFuture<String> future = responseFutures.remove(session.getId());
            if (future != null && !future.isDone()) future.completeExceptionally(e);
        }
    }

    /**
     * 构建 LlmMessage 列表
     * system prompt 内嵌规则，历史消息转换为 LlmMessage，最后追加当前用户问题
     */
    private List<LlmMessage> buildLlmMessages(String userMessage, List<Map<String, String>> history) {
        List<LlmMessage> messages = new ArrayList<>();

        // system prompt：从配置读取规则（可用工具由 AgentExecutor 通过 ToolDefinition 注入）
        messages.add(LlmMessage.system(aiProperties.getPrompt().getRules()));

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
     * 收尾：更新历史、发送完成通知、清理资源
     */
    private void finishResponse(WebSocketSession session, String conversationId,
                                String userId, String userMessage,
                                CompletableFuture<String> responseFuture) {
        StringBuilder builder = responseBuilders.get(session.getId());
        StringBuilder thinkingBuilder = thinkingBuilders.get(session.getId());

        String completeResponse = builder != null ? builder.toString() : "";
        String thinkingContent = thinkingBuilder != null ? thinkingBuilder.toString() : "";

        if (!completeResponse.isEmpty()) {
            updateConversationHistory(conversationId, userMessage, completeResponse, thinkingContent);
        }

        try {
            conversationSessionService.refreshSessionTTL(userId, conversationId);
        } catch (Exception e) {
            logger.warn("刷新会话TTL失败: {}", e.getMessage());
        }

        sendCompletionNotification(session);
        cleanupSession(session.getId());
        responseFutures.remove(session.getId());
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

    private void updateConversationHistory(String conversationId, String userMessage,
                                           String response, String thinkingContent) {
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

        if (thinkingContent != null && !thinkingContent.isEmpty()) {
            int maxLen = aiProperties.getThinking().getMaxPersistLength();
            if (thinkingContent.length() > maxLen) {
                thinkingContent = thinkingContent.substring(0, maxLen) + "\n\n[思考过程内容过长，已截断]";
                logger.warn("思考过程内容超过 {} 字符，已截断", maxLen);
            }
            assistantMsg.put("thinkingContent", thinkingContent);
        }
        history.add(assistantMsg);

        if (history.size() > 20) history = history.subList(history.size() - 20, history.size());

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (JsonProcessingException e) {
            logger.error("序列化对话历史出错: {}", e.getMessage(), e);
        }
    }

    private void sendThinkingChunk(WebSocketSession session, String chunk) {
        try {
            if (!aiProperties.getThinking().isEnabled()) return;
            if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) return;
            String json = objectMapper.writeValueAsString(Map.of("type", "thinking", "chunk", chunk));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("发送思考块失败: {}", e.getMessage(), e);
        }
    }

    private void sendAnswerChunk(WebSocketSession session, String chunk) {
        try {
            if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) return;
            String json = objectMapper.writeValueAsString(Map.of("type", "answer", "chunk", chunk));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("发送回答块失败: {}", e.getMessage(), e);
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

    /**
     * 统一清理指定 session 关联的所有临时数据
     */
    public void cleanupSession(String sessionId) {
        responseBuilders.remove(sessionId);
        thinkingBuilders.remove(sessionId);
        stopFlags.remove(sessionId);
        sessionStartTimes.remove(sessionId);
    }

    /**
     * 定时清理超过 10 分钟未完成的 stale session 数据，防止内存泄漏
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupStaleBuilders() {
        long now = System.currentTimeMillis();
        sessionStartTimes.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 600000) {
                String sessionId = entry.getKey();
                logger.warn("定时清理残留 session 数据: {}", sessionId);
                responseBuilders.remove(sessionId);
                thinkingBuilders.remove(sessionId);
                stopFlags.remove(sessionId);
                return true;
            }
            return false;
        });
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
        toolCleanupExecutor.schedule(() -> stopFlags.remove(sessionId), 2, TimeUnit.SECONDS);
    }
}
