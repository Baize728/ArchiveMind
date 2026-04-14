package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.agent.AgentCallback;
import com.zyh.archivemind.agent.AgentConfig;
import com.zyh.archivemind.agent.AgentContext;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
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

    private ChatHandler chatHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatHandler = new ChatHandler(
                redisTemplate, conversationSessionService,
                preferenceService, agentExecutor, new AiProperties());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("test-session-id");
        lenient().when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
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
}
