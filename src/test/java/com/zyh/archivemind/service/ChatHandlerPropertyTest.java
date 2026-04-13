package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatHandler 属性测试
 * 验证消息始终存储在活跃会话下，以及无活跃会话时自动创建
 */
class ChatHandlerPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Property: 消息始终存储在活跃会话下
     * 无论 userId 和消息内容如何，历史都写入 conversation:{activeSessionId}
     */
    @Property(tries = 50)
    void messagesAlwaysStoredUnderActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String messageContent
    ) throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HybridSearchService searchService = mock(HybridSearchService.class);
        ConversationSessionService conversationSessionService = mock(ConversationSessionService.class);
        LlmRouter llmRouter = mock(LlmRouter.class);
        ToolCallParser toolCallParser = mock(ToolCallParser.class);
        UserLlmPreferenceService preferenceService = mock(UserLlmPreferenceService.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(wsSession.getId()).thenReturn("ws-" + UUID.randomUUID());
        when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        when(llmProvider.supportsToolCalling()).thenReturn(false);

        String activeSessionId = UUID.randomUUID().toString();
        when(conversationSessionService.getActiveSessionId(userId)).thenReturn(activeSessionId);
        String expectedKey = "conversation:" + activeSessionId;
        when(valueOperations.get(expectedKey)).thenReturn(null);

        // LLM 直接返回文本
        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            cb.onTextChunk("AI回复");
            cb.onComplete();
            return null;
        }).when(llmProvider).streamChat(any(), any());

        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, searchService, conversationSessionService,
                llmRouter, toolCallParser, preferenceService, new AiProperties());

        // Act
        chatHandler.processMessage(userId, messageContent, wsSession);

        // Assert: 历史写入正确的 key
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).set(
                keyCaptor.capture(), jsonCaptor.capture(), any(Duration.class));

        assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);

        // 验证存储内容包含用户消息和 AI 回复
        @SuppressWarnings("unchecked")
        List<Map<String, String>> stored = objectMapper.readValue(
                jsonCaptor.getValue(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        assertThat(stored).hasSizeGreaterThanOrEqualTo(2);
        assertThat(stored.get(stored.size() - 2).get("role")).isEqualTo("user");
        assertThat(stored.get(stored.size() - 2).get("content")).isEqualTo(messageContent);
        assertThat(stored.get(stored.size() - 1).get("role")).isEqualTo("assistant");
    }

    /**
     * Property: 无活跃会话时自动创建新会话
     */
    @Property(tries = 50)
    void autoCreateSessionWhenNoActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String messageContent
    ) throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HybridSearchService searchService = mock(HybridSearchService.class);
        ConversationSessionService conversationSessionService = mock(ConversationSessionService.class);
        LlmRouter llmRouter = mock(LlmRouter.class);
        ToolCallParser toolCallParser = mock(ToolCallParser.class);
        UserLlmPreferenceService preferenceService = mock(UserLlmPreferenceService.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(wsSession.getId()).thenReturn("ws-" + UUID.randomUUID());
        when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        when(llmProvider.supportsToolCalling()).thenReturn(false);

        // 无活跃会话
        when(conversationSessionService.getActiveSessionId(userId)).thenReturn(null);
        String newSessionId = UUID.randomUUID().toString();
        when(conversationSessionService.createSession(userId))
                .thenReturn(new SessionDTO(newSessionId, "新对话", LocalDateTime.now()));

        String expectedKey = "conversation:" + newSessionId;
        when(valueOperations.get(expectedKey)).thenReturn(null);

        doAnswer(inv -> {
            LlmStreamCallback cb = inv.getArgument(1);
            cb.onTextChunk("AI回复");
            cb.onComplete();
            return null;
        }).when(llmProvider).streamChat(any(), any());

        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, searchService, conversationSessionService,
                llmRouter, toolCallParser, preferenceService, new AiProperties());

        // Act
        chatHandler.processMessage(userId, messageContent, wsSession);

        // Assert: 自动创建了新会话
        verify(conversationSessionService, times(1)).createSession(userId);

        // 历史写入新会话的 key
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).set(
                keyCaptor.capture(), anyString(), any(Duration.class));
        assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);
    }
}
