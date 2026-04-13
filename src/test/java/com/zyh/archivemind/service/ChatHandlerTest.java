package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
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
    @Mock private HybridSearchService searchService;
    @Mock private ConversationSessionService conversationSessionService;
    @Mock private LlmRouter llmRouter;
    @Mock private ToolCallParser toolCallParser;
    @Mock private UserLlmPreferenceService preferenceService;
    @Mock private LlmProvider llmProvider;
    @Mock private WebSocketSession session;

    private ChatHandler chatHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatHandler = new ChatHandler(
                redisTemplate, searchService, conversationSessionService,
                llmRouter, toolCallParser, preferenceService, new AiProperties());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("test-session-id");
        lenient().when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        lenient().when(llmProvider.supportsToolCalling()).thenReturn(false);
    }

    @Test
    @DisplayName("无活跃会话时应自动创建新会话")
    void shouldAutoCreateSessionWhenNoActiveSession() {
        when(conversationSessionService.getActiveSessionId("user1")).thenReturn(null);
        when(conversationSessionService.createSession("user1"))
                .thenReturn(new SessionDTO("new-session-id", "新对话", LocalDateTime.now()));
        when(valueOperations.get(anyString())).thenReturn(null);

        // LLM 直接返回文本（不调用工具）
        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            cb.onTextChunk("回复内容");
            cb.onComplete();
            return null;
        }).when(llmProvider).streamChat(any(), any());

        chatHandler.processMessage("user1", "你好", session);

        verify(conversationSessionService).createSession("user1");
    }

    @Test
    @DisplayName("LLM 直接回答时应正确保存对话历史")
    void shouldSaveHistoryWhenLlmAnswersDirectly() throws Exception {
        when(conversationSessionService.getActiveSessionId("user2")).thenReturn("conv-123");
        when(valueOperations.get("conversation:conv-123")).thenReturn(null);

        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            cb.onTextChunk("这是AI的回答");
            cb.onComplete();
            return null;
        }).when(llmProvider).streamChat(any(), any());

        chatHandler.processMessage("user2", "什么是RAG？", session);

        // 验证历史被写入 Redis
        verify(valueOperations).set(eq("conversation:conv-123"), anyString(), any());
    }

    @Test
    @DisplayName("LLM 调用工具时应执行搜索并继续对话")
    void shouldExecuteToolCallAndContinue() throws Exception {
        when(conversationSessionService.getActiveSessionId("user3")).thenReturn("conv-456");
        when(valueOperations.get("conversation:conv-456")).thenReturn(null);
        when(llmProvider.supportsToolCalling()).thenReturn(true);
        when(toolCallParser.parseArguments(any())).thenReturn(Map.of("query", "档案管理规范"));
        when(searchService.searchWithPermission(anyString(), eq("user3"), eq(5))).thenReturn(List.of());
        when(toolCallParser.serializeResult(any())).thenReturn("[]");

        // 第一轮：返回 tool_call；第二轮：返回文本
        final int[] callCount = {0};
        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            callCount[0]++;
            if (callCount[0] == 1) {
                cb.onToolCall(ToolCall.builder()
                        .id("call-1")
                        .functionName("knowledge_search")
                        .arguments("{\"query\":\"档案管理规范\"}")
                        .build());
                cb.onComplete();
            } else {
                cb.onTextChunk("根据搜索结果...");
                cb.onComplete();
            }
            return null;
        }).when(llmProvider).streamChat(any(), any());

        chatHandler.processMessage("user3", "档案管理有什么规范？", session);

        // 等待异步工具执行完成
        Thread.sleep(500);

        verify(searchService).searchWithPermission(eq("档案管理规范"), eq("user3"), eq(5));
    }

    @Test
    @DisplayName("工具调用达到上限时应强制结束")
    void shouldForceFinishWhenMaxIterationsReached() throws Exception {
        when(conversationSessionService.getActiveSessionId("user4")).thenReturn("conv-789");
        when(valueOperations.get("conversation:conv-789")).thenReturn(null);
        when(llmProvider.supportsToolCalling()).thenReturn(true);
        when(toolCallParser.parseArguments(any())).thenReturn(Map.of("query", "test"));
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(toolCallParser.serializeResult(any())).thenReturn("[]");

        // 每轮都返回 tool_call，触发上限
        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            cb.onToolCall(ToolCall.builder()
                    .id("call-x")
                    .functionName("knowledge_search")
                    .arguments("{\"query\":\"test\"}")
                    .build());
            cb.onComplete();
            return null;
        }).when(llmProvider).streamChat(any(), any());

        chatHandler.processMessage("user4", "一直搜索", session);

        // 等待所有异步循环完成（最多 5 轮）
        Thread.sleep(1000);

        // streamChat 最多被调用 MAX_TOOL_ITERATIONS(5) 次
        verify(llmProvider, atMost(5)).streamChat(any(), any());
    }
}
