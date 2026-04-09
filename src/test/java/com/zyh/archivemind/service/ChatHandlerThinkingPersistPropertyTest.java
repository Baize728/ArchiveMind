package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature: ai-streaming-thinking-display, Property 3: thinkingContent 持久化 round-trip 一致性
 *
 * Validates: Requirements 2.5
 *
 * For any thinkingContent string (length within maxPersistLength), after writing
 * to Redis via updateConversationHistory and reading back, the thinkingContent
 * in the assistant message should match the original input exactly.
 */
class ChatHandlerThinkingPersistPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Feature: ai-streaming-thinking-display, Property 3: thinkingContent 持久化 round-trip 一致性
     *
     * Validates: Requirements 2.5
     *
     * Strategy:
     * 1. Mock deepSeekClient.streamResponse to invoke onThinkingChunk with the
     *    generated thinkingContent, then onAnswerChunk with a fixed answer, then onComplete.
     * 2. Capture the Redis opsForValue().set() call via ArgumentCaptor.
     * 3. Parse the stored JSON and verify the thinkingContent in the assistant
     *    message matches the original input exactly (no mutation, no truncation).
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 3: thinkingContent 持久化 round-trip 一致性")
    void thinkingContentRoundTripConsistency(
            @ForAll @StringLength(min = 1, max = 1000) String thinkingContent
    ) throws Exception {
        // Arrange: create mocks
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HybridSearchService searchService = mock(HybridSearchService.class);
        DeepSeekClient deepSeekClient = mock(DeepSeekClient.class);
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        ConversationSessionService conversationSessionService = mock(ConversationSessionService.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String wsSessionId = "ws-" + UUID.randomUUID();
        when(wsSession.getId()).thenReturn(wsSessionId);
        when(wsSession.isOpen()).thenReturn(true);

        // Set up an active session
        String activeSessionId = UUID.randomUUID().toString();
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn(activeSessionId);

        String expectedKey = "conversation:" + activeSessionId;
        when(valueOperations.get(expectedKey)).thenReturn(null);

        // Mock query rewrite and search
        when(queryRewriteService.rewrite(anyString(), anyList())).thenReturn("rewritten");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // Mock DeepSeekClient to invoke onThinkingChunk with the test content,
        // then onAnswerChunk with a fixed answer, then onComplete
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onThinkingChunk = invocation.getArgument(3);
            @SuppressWarnings("unchecked")
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onThinkingChunk.accept(thinkingContent);
            onAnswerChunk.accept("测试回答");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(
                anyString(), anyString(), anyList(), any(), any(), any(), any());

        // AiProperties with default maxPersistLength = 20000
        AiProperties aiProperties = new AiProperties();

        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService, aiProperties);

        // Act: process the message
        chatHandler.processMessage("testUser", "测试问题", wsSession);

        // Assert: capture the Redis set() call
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).set(
                keyCaptor.capture(), jsonCaptor.capture(), any(Duration.class));

        // Parse the stored JSON
        String storedJson = jsonCaptor.getValue();
        List<Map<String, String>> storedHistory = MAPPER.readValue(
                storedJson, new TypeReference<List<Map<String, String>>>() {});

        // Find the assistant message (last message)
        Map<String, String> assistantMsg = storedHistory.get(storedHistory.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");

        // Verify thinkingContent round-trip: stored value must equal original input
        assertThat(assistantMsg.get("thinkingContent")).isEqualTo(thinkingContent);
    }
}
