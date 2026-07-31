package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.Llm.UserLlmPreferenceService;
import com.zyh.archivemind.Tool.ToolCall;
import com.zyh.archivemind.agent.AgentCallback;
import com.zyh.archivemind.agent.AgentConfig;
import com.zyh.archivemind.agent.AgentContext;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.intent.Intent;
import com.zyh.archivemind.intent.IntentResult;
import com.zyh.archivemind.intent.IntentRouter;
import com.zyh.archivemind.fallback.FallbackContext;
import com.zyh.archivemind.fallback.FallbackPolicyService;
import com.zyh.archivemind.clarify.*;
import com.zyh.archivemind.model.SessionState;
import com.zyh.archivemind.trace.TraceCollector;
import com.zyh.archivemind.trace.TraceScope;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.Tool.Tool;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final TraceCollector traceCollector;
    private final IntentRouter intentRouter;
    private final com.zyh.archivemind.client.IntentLlmClient intentLlmClient;
    // T1-2 新增依赖
    private final QueryRewriteService queryRewriteService;
    private final SessionStateService sessionStateService;
    private final SlotExtractor slotExtractor;
    private final ClarifyRuleService clarifyRuleService;
    private final ClarifyAgentService clarifyAgentService;
    private final com.zyh.archivemind.fallback.FallbackPolicyService fallbackPolicyService;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> thinkingBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    /** Q9 并发控制：按 conversationId 串行化整个意图识别+澄清+检索流程 */
    private final Map<String, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

    /** 用于延迟清理 stopFlag */
    private final java.util.concurrent.ScheduledExecutorService toolCleanupExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "stop-flag-cleanup"));

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       ConversationSessionService conversationSessionService,
                       UserLlmPreferenceService preferenceService,
                       AgentExecutor agentExecutor,
                       AiProperties aiProperties,
                       TraceCollector traceCollector,
                       IntentRouter intentRouter,
                       com.zyh.archivemind.client.IntentLlmClient intentLlmClient,
                       QueryRewriteService queryRewriteService,
                       SessionStateService sessionStateService,
                       SlotExtractor slotExtractor,
                       ClarifyRuleService clarifyRuleService,
                       ClarifyAgentService clarifyAgentService,
                       com.zyh.archivemind.fallback.FallbackPolicyService fallbackPolicyService) {
        this.redisTemplate = redisTemplate;
        this.conversationSessionService = conversationSessionService;
        this.preferenceService = preferenceService;
        this.agentExecutor = agentExecutor;
        this.aiProperties = aiProperties;
        this.objectMapper = new ObjectMapper();
        this.traceCollector = traceCollector;
        this.intentRouter = intentRouter;
        this.intentLlmClient = intentLlmClient;
        this.queryRewriteService = queryRewriteService;
        this.sessionStateService = sessionStateService;
        this.slotExtractor = slotExtractor;
        this.clarifyRuleService = clarifyRuleService;
        this.clarifyAgentService = clarifyAgentService;
        this.fallbackPolicyService = fallbackPolicyService;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, session.getId());
        String conversationId = null;
        // Trace 作用域持有器（effectively-final，便于在回调 lambda 与 catch 中共享同一引用）
        // 受 trace.online-persist / sampling 控制，未命中时 openTrace 返回 noop
        final AtomicReference<TraceScope> traceScopeRef = new AtomicReference<>(TraceScope.noop());
        try {
            conversationId = getOrCreateConversationId(userId);

            // Q9 并发控制：按 conversationId 串行化整个流程
            ReentrantLock lock = conversationLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
            lock.lock();
            try {
                processMessageInternal(userId, userMessage, session, conversationId, traceScopeRef);
            } finally {
                lock.unlock();
            }

        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            TraceScope scope = traceScopeRef.get();
            String traceId = scope.getTraceId();
            scope.recordError(e.getMessage());
            scope.close();
            handleError(session, e, conversationId, userId, userMessage, traceId);
            cleanupSession(session.getId());
            CompletableFuture<String> future = responseFutures.remove(session.getId());
            if (future != null && !future.isDone()) future.completeExceptionally(e);
        }
    }

    /**
     * 消息处理内部逻辑（在并发锁内执行，T1-2 改造）。
     */
    private void processMessageInternal(String userId, String userMessage, WebSocketSession session,
                                         String conversationId,
                                         AtomicReference<TraceScope> traceScopeRef) {
        final String convId = conversationId;
        responseBuilders.put(session.getId(), new StringBuilder());
        thinkingBuilders.put(session.getId(), new StringBuilder());
        sessionStartTimes.put(session.getId(), System.currentTimeMillis());
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        responseFutures.put(session.getId(), responseFuture);

        // 开启 Trace
        TraceScope traceScope = traceCollector.openTrace(conversationId, userId, session.getId(), false);
        traceScopeRef.set(traceScope);
        traceScope.recordUserInput(userMessage);

        final long agentStartTime = System.currentTimeMillis();

        List<Map<String, String>> history = getConversationHistory(conversationId);

        if (history.isEmpty()) {
            try {
                conversationSessionService.autoGenerateTitle(conversationId, userMessage);
            } catch (Exception e) {
                logger.warn("自动生成标题失败，会话ID: {}, 错误: {}", conversationId, e.getMessage());
            }
        }

        // ========== T1-2 1. 读取 SessionState（Q16 方案 A）==========
        SessionState state = sessionStateService.get(conversationId);

        // ========== T1-2 2. QueryRewrite（所有意图前置，ADR-012）==========
        long rewriteStart = System.currentTimeMillis();
        String rewrittenQuery = queryRewriteService.rewrite(userMessage, history);
        long rewriteLatency = System.currentTimeMillis() - rewriteStart;
        // 记录 trace（无历史时 rewriteService 内部直接返回原 query，不调 LLM，latency~0）
        traceScope.recordQueryRewrite(userMessage, rewrittenQuery, rewriteLatency);

        // ========== T1-2 3. 意图识别（用改写后的 query）==========
        IntentResult intentResult = intentRouter.route(rewrittenQuery, history, state, traceScope);
        traceScope.recordIntent(rewrittenQuery, intentResult.intent().name(),
                intentResult.confidence(), intentResult.source());

        // 统一写入 lastIntent（Q21）
        state = state.withLastIntent(intentResult.intent());
        sessionStateService.update(conversationId, state);

        // Q18：换话题重置（上一轮 KNOWLEDGE_QA 且本轮不是 → 重置 clarifyTurn + lastAskedFields）
        if (state.clarifyTurn() > 0
                && state.lastIntent() == Intent.KNOWLEDGE_QA
                && intentResult.intent() != Intent.KNOWLEDGE_QA) {
            state = state.resetClarify();
            sessionStateService.update(conversationId, state);
        }

        // ========== T1-2 4. 路由分发 ==========
        switch (intentResult.intent()) {
            case CHITCHAT -> {
                handleChitchat(rewrittenQuery, session, convId, userId, traceScope, responseFuture);
                return;
            }
            case AMBIGUOUS -> {
                handleAmbiguous(session, convId, userId, userMessage, traceScope, responseFuture);
                return;
            }
            case DOC_OPERATION -> {
                // 落到下方 AgentExecutor，用 rewrittenQuery
            }
            case KNOWLEDGE_QA -> {
                logger.info("[DEBUG-CLARIFY] entering KNOWLEDGE_QA case, clarify.enabled={}", aiProperties.getClarify().isEnabled());
                // ========== T1-2 澄清流程 ==========
                if (aiProperties.getClarify().isEnabled()) {
                    ClarifyResult clarifyResult = runClarify(rewrittenQuery, state, conversationId, traceScope);
                    if (clarifyResult.action() == ClarifyResult.ClarifyAction.ASK) {
                        handleClarifyAsk(session, convId, userId, userMessage, clarifyResult,
                                conversationId, traceScope, responseFuture);
                        return;  // 不进 AgentExecutor
                    }
                    // READY → Q19 清理 SessionState
                    state = state.onReady();
                    sessionStateService.update(conversationId, state);
                }
                // 继续走 AgentExecutor
            }
        }

        // ========== 原 AgentExecutor 路径（用改写后的 query）==========
        List<LlmMessage> messages = buildLlmMessages(rewrittenQuery, history);

        LlmProvider provider = preferenceService.getProviderForUser(userId);

        AgentConfig config = AgentConfig.builder()
                .maxIterations(5)
                .build();

        Tool.ToolContext toolContext = new Tool.ToolContext(userId, session.getId(), conversationId);

        AgentContext agentContext = AgentContext.builder()
                .toolContext(toolContext)
                .messages(messages)
                .build();
        agentContext.setTraceScope(traceScope);

        agentExecutor.execute(provider, config, agentContext, new AgentCallback() {
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
            public void onToolCallEnd(ToolCall toolCall, Tool.ToolResult result) {
                sendToolCallNotification(session, toolCall,
                        result.success() ? "done" : "failed");
            }

            @Override
            public void onComplete() {
                TraceScope scope = traceScopeRef.get();
                String traceId = scope.getTraceId();

                // T1-5 空文本兜底（Q7/Q11）：正常完成空文本=故障；maxIter截断空文本=异常截断
                String existing = responseBuilders.get(session.getId()).toString();
                if (existing.isBlank()) {
                    FallbackContext ctx = FallbackContext.builder()
                            .stage("answer")
                            .traceScope(scope)
                            .error(null)
                            .existingResponse(existing)
                            .build();
                    String template = (String) fallbackPolicyService.execute("answer", ctx);
                    sendAnswerChunk(session, template);
                }

                scope.recordAgentDuration(System.currentTimeMillis() - agentStartTime);
                scope.close();
                finishResponse(session, convId, userId, userMessage, responseFuture, traceId);
            }

            @Override
            public void onError(Throwable error) {
                TraceScope scope = traceScopeRef.get();
                String traceId = scope.getTraceId();

                // T1-5 答案生成断流兜底（Q7/Q9/Q10）
                String existing = responseBuilders.get(session.getId()).toString();

                // 清空半截 thinking——不存进历史、不渲染前端 ThinkingSection（Q10）
                StringBuilder thinkingBuilder = thinkingBuilders.get(session.getId());
                if (thinkingBuilder != null) thinkingBuilder.setLength(0);

                // 调 FallbackPolicyService 获取模板（全空 vs 部分断流由 fallbackAnswer 内部判定）
                FallbackContext ctx = FallbackContext.builder()
                        .stage("answer")
                        .traceScope(scope)
                        .error(error)
                        .existingResponse(existing)
                        .build();
                String template = (String) fallbackPolicyService.execute("answer", ctx);
                sendAnswerChunk(session, template);

                scope.recordAgentDuration(System.currentTimeMillis() - agentStartTime);
                scope.close();

                // 走 finishResponse 正常收尾——内部已含 cleanupSession + responseFutures.remove + responseFuture.complete（Q9）
                // 不走 handleError——避免前端置 error 态显示红字 + 避免 completeExceptionally 让前端判失败
                finishResponse(session, convId, userId, userMessage, responseFuture, traceId);
            }
        });
    }

    /**
     * T1-2 澄清流程（§6.2）。
     * 槽位抽取 → 超限检查 → 规则判缺失 → LLM 生成追问。
     *
     * @return ClarifyResult（ASK 或 READY）
     */
    private ClarifyResult runClarify(String query, SessionState state,
                                     String conversationId, TraceScope traceScope) {
        logger.info("[DEBUG-CLARIFY] runClarify called, query={}, initialState.clarifyTurn={}, initialState.slots={}", query, state.clarifyTurn(), state.slots());
        long clarifyStart = System.currentTimeMillis();
        ClarifyResult result = null;
        List<String> finalMissing = List.of();
        try {
            // 1. 槽位抽取（用改写后的 query）
            SlotBundle extracted = slotExtractor.extract(query, traceScope);
            logger.info("[DEBUG-CLARIFY] extracted slots: {}", extracted);
            // 覆盖合并（Q19）：抽到即覆盖旧值，抽不到保留旧值
            SlotBundle merged = mergeSlots(state.slots(), extracted);
            logger.info("[DEBUG-CLARIFY] merged slots: {}", merged);

            // 2. 更新 state.slots
            state = state.withSlots(merged);
            sessionStateService.update(conversationId, state);

            // 3. Q6 超限放行
            if (state.clarifyTurn() >= aiProperties.getClarify().getMaxClarifyTurns()) {
                String disclaimer = aiProperties.getClarify().getExhaustedDisclaimer();
                logger.info("[DEBUG-CLARIFY] max turns reached, READY with disclaimer");
                result = ClarifyResult.readyWithDisclaimer(disclaimer);
                return result;
            }

            // 4. 规则判缺失
            List<String> missing = clarifyRuleService.missingSlots(merged, state);
            finalMissing = missing;
            logger.info("[DEBUG-CLARIFY] missing slots: {}", missing);

            if (missing.isEmpty()) {
                logger.info("[DEBUG-CLARIFY] no missing slots, READY");
                result = ClarifyResult.ready();
                return result;
            }

            // 5. LLM 生成追问
            result = clarifyAgentService.decide(
                    query, merged, missing, state.clarifyTurn(), traceScope);

            // 6. ASK 时更新 state（Q18 计数 + Q13 lastAskedFields）
            if (result.action() == ClarifyResult.ClarifyAction.ASK) {
                state = state.onAsk(missing.get(0));
                sessionStateService.update(conversationId, state);
            }

            return result;
        } finally {
            // 记录 trace
            long latency = System.currentTimeMillis() - clarifyStart;
            String action = result != null ? result.action().name() : "ERROR";
            String question = result != null ? result.questionToAsk() : null;
            try {
                String slotsJson = String.format("{\"domain\":%s,\"entity\":%s,\"docScope\":%s,\"timeRange\":%s}",
                        state.slots().domain() == null ? "null" : "\"" + state.slots().domain() + "\"",
                        state.slots().entity() == null ? "null" : "\"" + state.slots().entity() + "\"",
                        state.slots().docScope() == null ? "null" : "\"" + state.slots().docScope() + "\"",
                        state.slots().timeRange() == null ? "null" : "\"" + state.slots().timeRange() + "\"");
                String missingJson = finalMissing.isEmpty() ? "[]" : "[\"" + String.join("\",\"", finalMissing) + "\"]";
                traceScope.recordClarify(query, slotsJson, missingJson, action, question, latency);
            } catch (Exception e) {
                logger.warn("记录 clarify trace 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 覆盖合并槽位（Q19）：current 非空覆盖 history，current 为空保留 history。
     */
    private SlotBundle mergeSlots(SlotBundle history, SlotBundle current) {
        if (history == null) history = SlotBundle.empty();
        if (current == null) current = SlotBundle.empty();
        return new SlotBundle(
                current.domain() != null ? current.domain() : history.domain(),
                current.docScope() != null ? current.docScope() : history.docScope(),
                current.timeRange() != null ? current.timeRange() : history.timeRange(),
                current.entity() != null ? current.entity() : history.entity());
    }

    /**
     * 澄清追问处理：同步返回追问文案（Q7），不进 AgentExecutor。
     */
    private void handleClarifyAsk(WebSocketSession session, String convId, String userId,
                                    String userMessage, ClarifyResult clarifyResult,
                                    String conversationId, TraceScope traceScope,
                                    CompletableFuture<String> responseFuture) {
        long startTime = System.currentTimeMillis();
        try {
            String reply = clarifyResult.questionToAsk();
            if (reply == null || reply.isBlank()) {
                reply = clarifyRuleService.fallbackQuestion(clarifyResult.missingSlots(), false);
            }

            StringBuilder builder = responseBuilders.get(session.getId());
            if (builder != null) builder.append(reply);
            sendAnswerChunk(session, reply);  // 同步返回（Q7）

            traceScope.recordAgentDuration(System.currentTimeMillis() - startTime);
            String traceId = traceScope.getTraceId();
            traceScope.close();
            finishResponse(session, convId, userId, userMessage, responseFuture, traceId);
        } catch (Exception e) {
            logger.error("澄清追问处理失败: {}", e.getMessage(), e);
            String traceId = traceScope.getTraceId();
            traceScope.recordError(e.getMessage());
            traceScope.close();
            handleError(session, e, convId, userId, userMessage, traceId);
            cleanupSession(session.getId());
            responseFutures.remove(session.getId());
        }
    }

    /**
     * 构建 LlmMessage 列表
     * system prompt 内嵌规则，历史消息转换为 LlmMessage，最后追加当前用户问题
     */
    private List<LlmMessage> buildLlmMessages(String userMessage, List<Map<String, String>> history) {
        List<LlmMessage> messages = new ArrayList<>();

        // system prompt：从配置读取规则（可用工具由 AgentExecutor 通过 Tool 接口注入）
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
     * 闲聊处理：用 IntentLlmClient 直回，不走工具/检索（T1-1）。
     */
    private void handleChitchat(String userMessage, WebSocketSession session,
                                String convId, String userId,
                                TraceScope traceScope, CompletableFuture<String> responseFuture) {
        long startTime = System.currentTimeMillis();
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是ArchiveMind知识助手，用户在和您寒暄。请简短友好地回复一句话，引导用户提出知识库相关的问题。"),
                    Map.of("role", "user", "content", userMessage)
            );
            String reply = intentLlmClient.chatSync(messages);
            if (reply == null || reply.isBlank()) {
                // T1-5：闲聊兜底走 FallbackPolicyService（Q5/Q12）
                FallbackContext ctx = FallbackContext.builder()
                        .stage("chitchat")
                        .traceScope(traceScope)
                        .build();
                reply = (String) fallbackPolicyService.execute("chitchat", ctx);
            }

            // 一次性推送回复
            StringBuilder builder = responseBuilders.get(session.getId());
            if (builder != null) builder.append(reply);
            sendAnswerChunk(session, reply);

            traceScope.recordAgentDuration(System.currentTimeMillis() - startTime);
            String traceId = traceScope.getTraceId();
            traceScope.close();
            finishResponse(session, convId, userId, userMessage, responseFuture, traceId);
        } catch (Exception e) {
            logger.error("闲聊处理失败: {}", e.getMessage(), e);
            String traceId = traceScope.getTraceId();
            traceScope.recordError(e.getMessage());
            traceScope.close();
            handleError(session, e, convId, userId, userMessage, traceId);
            cleanupSession(session.getId());
            responseFutures.remove(session.getId());
        }
    }

    /**
     * 模糊意图处理：返回固定提示文案，不进 RAG（T1-1）。
     * T1-2 接入澄清层后，此方法将被替换为真实澄清逻辑。
     */
    private void handleAmbiguous(WebSocketSession session, String convId, String userId,
                                  String userMessage, TraceScope traceScope,
                                  CompletableFuture<String> responseFuture) {
        long startTime = System.currentTimeMillis();
        try {
            String reply = aiProperties.getIntent().getAmbiguousReply();

            StringBuilder builder = responseBuilders.get(session.getId());
            if (builder != null) builder.append(reply);
            sendAnswerChunk(session, reply);

            traceScope.recordAgentDuration(System.currentTimeMillis() - startTime);
            String traceId = traceScope.getTraceId();
            traceScope.close();
            finishResponse(session, convId, userId, userMessage, responseFuture, traceId);
        } catch (Exception e) {
            logger.error("模糊意图处理失败: {}", e.getMessage(), e);
            String traceId = traceScope.getTraceId();
            traceScope.recordError(e.getMessage());
            traceScope.close();
            handleError(session, e, convId, userId, userMessage, traceId);
            cleanupSession(session.getId());
            responseFutures.remove(session.getId());
        }
    }

    /**
     * 收尾：更新历史、发送完成通知、清理资源
     */
    private void finishResponse(WebSocketSession session, String conversationId,
                                String userId, String userMessage,
                                CompletableFuture<String> responseFuture, String traceId) {
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

        sendCompletionNotification(session, traceId);
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
                    "function", toolCall.functionName(),
                    "status", status
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("发送工具调用通知失败: {}", e.getMessage(), e);
        }
    }

    private void sendCompletionNotification(WebSocketSession session, String traceId) {
        try {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("type", "completion");
            notification.put("status", "finished");
            notification.put("message", "响应已完成");
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("traceId", traceId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("发送完成通知失败: {}", e.getMessage(), e);
        }
    }

    private void handleError(WebSocketSession session, Throwable error,
                             String conversationId, String userId, String userMessage,
                             String traceId) {
        logger.error("AI服务错误: {}", error.getMessage(), error);
        String fallbackReply = aiProperties.getFallback().getErrorTemplate();
        try {
            Map<String, Object> errorFrame = new LinkedHashMap<>();
            errorFrame.put("error", fallbackReply);
            errorFrame.put("traceId", traceId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorFrame)));
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }

        // 即使 LLM 失败，也要把用户问题和兜底回复保存到对话历史，便于后续排查和 BadCase 回流
        if (conversationId != null && userMessage != null && !userMessage.isEmpty()) {
            try {
                updateConversationHistory(conversationId, userMessage, fallbackReply, null);
                if (userId != null) {
                    conversationSessionService.refreshSessionTTL(userId, conversationId);
                }
            } catch (Exception persistEx) {
                logger.warn("LLM 异常时保存对话历史失败: conversationId={}, 错误: {}",
                        conversationId, persistEx.getMessage());
            }
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
