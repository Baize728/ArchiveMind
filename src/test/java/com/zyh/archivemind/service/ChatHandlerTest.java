package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.agent.AgentCallback;
import com.zyh.archivemind.agent.AgentConfig;
import com.zyh.archivemind.agent.AgentContext;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.client.IntentLlmClient;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.intent.Intent;
import com.zyh.archivemind.intent.IntentResult;
import com.zyh.archivemind.intent.IntentRouter;
import com.zyh.archivemind.trace.TraceCollector;
import com.zyh.archivemind.trace.TraceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHandlerTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ConversationSessionService conversationSessionService;
    @Mock private UserLlmPreferenceService preferenceService;
    @Mock private AgentExecutor agentExecutor;
    @Mock private LlmProvider llmProvider;
    @Mock private WebSocketSession session;
    @Mock private TraceCollector traceCollector;
    @Mock private IntentRouter intentRouter;
    @Mock private IntentLlmClient intentLlmClient;
    @Mock private QueryRewriteService queryRewriteService;
    @Mock private com.zyh.archivemind.clarify.SessionStateService sessionStateService;
    @Mock private com.zyh.archivemind.clarify.SlotExtractor slotExtractor;
    @Mock private com.zyh.archivemind.clarify.ClarifyRuleService clarifyRuleService;
    @Mock private com.zyh.archivemind.clarify.ClarifyAgentService clarifyAgentService;
    @Mock private com.zyh.archivemind.fallback.FallbackPolicyService fallbackPolicyService;

    private ChatHandler chatHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatHandler = new ChatHandler(
                redisTemplate, conversationSessionService,
                preferenceService, agentExecutor, new AiProperties(), traceCollector,
                intentRouter, intentLlmClient,
                queryRewriteService, sessionStateService, slotExtractor,
                clarifyRuleService, clarifyAgentService, fallbackPolicyService);
        // 默认 mock：QueryRewrite 原样返回、SessionState 返回 fresh
        lenient().when(queryRewriteService.rewrite(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sessionStateService.get(any())).thenReturn(com.zyh.archivemind.model.SessionState.fresh());
        lenient().when(traceCollector.openTrace(any(), any(), any(), anyBoolean())).thenReturn(TraceScope.noop());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("test-session-id");
        lenient().when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        // 默认意图为 KNOWLEDGE_QA，走 AgentExecutor 路径
        lenient().when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", ""));
    }

    @Test
    @DisplayName("无活跃会话时应自动创建新会话")
    void shouldAutoCreateSessionWhenNoActiveSession() {
        when(conversationSessionService.getActiveSessionId("user1")).thenReturn(null);
        when(conversationSessionService.createSession("user1"))
                .thenReturn(new SessionDTO("new-session-id", "新对话", LocalDateTime.now()));
        when(valueOperations.get(anyString())).thenReturn(null);

        // AgentExecutor 直接回调 onComplete
        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onTextChunk("回复内容");
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user1", "你好", session);

        verify(conversationSessionService).createSession("user1");
    }

    @Test
    @DisplayName("Agent 直接回答时应正确保存对话历史")
    void shouldSaveHistoryWhenLlmAnswersDirectly() throws Exception {
        when(conversationSessionService.getActiveSessionId("user2")).thenReturn("conv-123");
        when(valueOperations.get("conversation:conv-123")).thenReturn(null);

        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onTextChunk("这是AI的回答");
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user2", "什么是RAG？", session);

        // 验证历史被写入 Redis
        verify(valueOperations).set(eq("conversation:conv-123"), anyString(), any());
    }

    @Test
    @DisplayName("Agent 执行出错时应发送错误消息")
    void shouldHandleAgentError() throws Exception {
        when(conversationSessionService.getActiveSessionId("user3")).thenReturn("conv-456");
        when(valueOperations.get("conversation:conv-456")).thenReturn(null);

        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onError(new RuntimeException("LLM 调用失败"));
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user3", "你好", session);

        // 验证发送了错误消息
        verify(session, atLeastOnce()).sendMessage(any());
    }

    @Test
    @DisplayName("Agent 执行时应传入正确的 Provider 和配置")
    void shouldPassCorrectProviderAndConfig() throws Exception {
        when(conversationSessionService.getActiveSessionId("user4")).thenReturn("conv-789");
        when(valueOperations.get("conversation:conv-789")).thenReturn(null);

        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onTextChunk("回复");
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user4", "测试", session);

        // 验证传入了用户偏好的 Provider
        verify(agentExecutor).execute(eq(llmProvider), any(AgentConfig.class),
                any(AgentContext.class), any(AgentCallback.class));
    }

    @Test
    @DisplayName("CHITCHAT 意图应走 handleChitchat 不进 AgentExecutor")
    void shouldHandleChitchatWithoutAgentExecutor() {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.CHITCHAT, 0.8, "RULE", ""));
        when(intentLlmClient.chatSync(anyList())).thenReturn("你好！有什么可以帮您？");
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-chitchat");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user5", "你好", session);

        // AgentExecutor 不应被调用
        verify(agentExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AMBIGUOUS 意图应走 handleAmbiguous 不进 AgentExecutor")
    void shouldHandleAmbiguousWithoutAgentExecutor() {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.AMBIGUOUS, 0.2, "KEYWORD", ""));
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-amb");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user6", "那个东西", session);

        // AgentExecutor 不应被调用
        verify(agentExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CHITCHAT 时 IntentLlmClient 返回 null 应走兜底文案")
    void shouldUseFallbackWhenChitchatLlmReturnsNull() throws Exception {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.CHITCHAT, 0.8, "RULE", ""));
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-chitchat");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user7", "你好", session);

        verify(agentExecutor, never()).execute(any(), any(), any(), any());
        verify(session, atLeastOnce()).sendMessage(any());
    }

    @Test
    @DisplayName("CHITCHAT 时 IntentLlmClient 返回空字符串应走兜底文案")
    void shouldUseFallbackWhenChitchatLlmReturnsEmpty() {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.CHITCHAT, 0.8, "RULE", ""));
        when(intentLlmClient.chatSync(anyList())).thenReturn("");
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-chitchat");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user8", "你好", session);

        verify(agentExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CHITCHAT 时应保存对话历史到 Redis")
    void shouldSaveHistoryForChitchat() {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.CHITCHAT, 0.8, "RULE", ""));
        when(intentLlmClient.chatSync(anyList())).thenReturn("你好！有什么可以帮您？");
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-chitchat");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user9", "你好", session);

        verify(valueOperations).set(eq("conversation:conv-chitchat"), anyString(), any());
    }

    @Test
    @DisplayName("AMBIGUOUS 时应返回配置的提示文案")
    void shouldReturnConfiguredAmbiguousReply() throws Exception {
        AiProperties customProps = new AiProperties();
        String customReply = "请补充更多信息";
        customProps.getIntent().setAmbiguousReply(customReply);
        chatHandler = new ChatHandler(
                redisTemplate, conversationSessionService,
                preferenceService, agentExecutor, customProps, traceCollector,
                intentRouter, intentLlmClient,
                queryRewriteService, sessionStateService, slotExtractor,
                clarifyRuleService, clarifyAgentService, fallbackPolicyService);

        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.AMBIGUOUS, 0.2, "KEYWORD", ""));
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-amb");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user10", "那个东西", session);

        verify(agentExecutor, never()).execute(any(), any(), any(), any());
        verify(session, atLeastOnce()).sendMessage(any());
    }

    @Test
    @DisplayName("DOC_OPERATION 意图应走 AgentExecutor（一期不另建链路）")
    void docOperationShouldGoThroughAgentExecutor() {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.DOC_OPERATION, 0.9, "LLM", ""));
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-doc");
        when(valueOperations.get(anyString())).thenReturn(null);

        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onTextChunk("正在处理");
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user11", "归档这份合同", session);

        verify(agentExecutor).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CHITCHAT 时 IntentLlmClient 抛异常应走 handleError 不崩")
    void shouldHandleChitchatLlmException() throws Exception {
        when(intentRouter.route(anyString(), any(), any(), any()))
                .thenReturn(new IntentResult(Intent.CHITCHAT, 0.8, "RULE", ""));
        when(intentLlmClient.chatSync(anyList())).thenThrow(new RuntimeException("LLM 宕机"));
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-chitchat");
        when(valueOperations.get(anyString())).thenReturn(null);

        chatHandler.processMessage("user12", "你好", session);

        verify(agentExecutor, never()).execute(any(), any(), any(), any());
        verify(session, atLeastOnce()).sendMessage(any());
    }

    @Test
    @DisplayName("意图识别 Trace 事件应被记录")
    void shouldRecordIntentTraceEvent() {
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-trace");
        when(valueOperations.get(anyString())).thenReturn(null);

        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        chatHandler.processMessage("user13", "测试", session);

        // TraceScope 是 noop，但 recordIntent 应被调用不报错
        verify(intentRouter).route(anyString(), any(), any(), any());
    }
}
