package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHandlerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HybridSearchService searchService;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private ConversationSessionService conversationSessionService;
    @Mock
    private WebSocketSession session;

    private ChatHandler chatHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        chatHandler = new ChatHandler(redisTemplate, searchService, deepSeekClient, queryRewriteService, conversationSessionService, aiProperties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("test-session-id");
    }

    @Test
    @DisplayName("processMessage应使用改写后的查询进行检索")
    void shouldUseRewrittenQueryForSearch() throws Exception {
        when(conversationSessionService.getActiveSessionId("user1")).thenReturn("conv-123");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "iPhone 14多少钱？"),
                Map.of("role", "assistant", "content", "iPhone 14的价格是5999元起。")
        );
        when(valueOperations.get("conversation:conv-123"))
                .thenReturn(objectMapper.writeValueAsString(history));

        when(queryRewriteService.rewrite(eq("那有分期吗？"), anyList()))
                .thenReturn("iPhone 14有分期付款服务吗？");

        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        chatHandler.processMessage("user1", "那有分期吗？", session);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchService).searchWithPermission(queryCaptor.capture(), eq("user1"), eq(5));
        assertThat(queryCaptor.getValue()).isEqualTo("iPhone 14有分期付款服务吗？");

        verify(deepSeekClient).streamResponse(eq("那有分期吗？"), anyString(), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("改写服务回退时应使用原始查询检索")
    void shouldUseOriginalQueryWhenRewriteFallsBack() throws Exception {
        when(conversationSessionService.getActiveSessionId("user2")).thenReturn("conv-456");
        when(valueOperations.get("conversation:conv-456")).thenReturn(null);

        when(queryRewriteService.rewrite(eq("什么是RAG？"), anyList()))
                .thenReturn("什么是RAG？");

        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        chatHandler.processMessage("user2", "什么是RAG？", session);

        verify(searchService).searchWithPermission(eq("什么是RAG？"), eq("user2"), eq(5));
    }

    @Test
    @DisplayName("对话历史应正确传递给QueryRewriteService")
    void shouldPassHistoryToRewriteService() throws Exception {
        when(conversationSessionService.getActiveSessionId("user3")).thenReturn("conv-789");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "问题1"),
                Map.of("role", "assistant", "content", "回答1"),
                Map.of("role", "user", "content", "问题2"),
                Map.of("role", "assistant", "content", "回答2")
        );
        when(valueOperations.get("conversation:conv-789"))
                .thenReturn(objectMapper.writeValueAsString(history));

        when(queryRewriteService.rewrite(anyString(), anyList())).thenReturn("改写后的问题3");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        chatHandler.processMessage("user3", "问题3", session);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(queryRewriteService).rewrite(eq("问题3"), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).hasSize(4);
    }

    @Test
    @DisplayName("搜索结果应基于改写查询构建上下文传给LLM")
    void shouldBuildContextFromRewrittenQueryResults() throws Exception {
        when(conversationSessionService.getActiveSessionId("user4")).thenReturn("conv-abc");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "介绍一下档案管理"),
                Map.of("role", "assistant", "content", "档案管理是...")
        );
        when(valueOperations.get("conversation:conv-abc"))
                .thenReturn(objectMapper.writeValueAsString(history));

        when(queryRewriteService.rewrite(eq("有什么规范？"), anyList()))
                .thenReturn("档案管理有什么规范？");

        List<SearchResult> results = List.of(
                new SearchResult("md5-1", 1, "档案管理规范第一条内容", 0.95, "user4", "ORG", true)
        );
        when(searchService.searchWithPermission(eq("档案管理有什么规范？"), eq("user4"), eq(5)))
                .thenReturn(results);

        chatHandler.processMessage("user4", "有什么规范？", session);

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient).streamResponse(eq("有什么规范？"), contextCaptor.capture(), anyList(), any(), any(), any(), any());
        assertThat(contextCaptor.getValue()).contains("档案管理规范第一条内容");
    }
}
