package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature: new-conversation, Property 7: 消息始终存储在活跃会话下
 *
 * Validates: Requirements 3.3, 6.1, 6.2, 6.4
 *
 * For any user, regardless of how their active session changes (creating new session
 * or switching to history session), messages sent via WebSocket are stored in the
 * current active session's conversation:{activeSessionId} key, not in any other
 * session's key.
 */
class ChatHandlerPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Feature: new-conversation, Property 7: 消息始终存储在活跃会话下
     *
     * Validates: Requirements 3.3, 6.1, 6.2, 6.4
     *
     * Verifies that for any user and message content:
     * 1. ChatHandler reads conversation history from conversation:{activeSessionId}
     * 2. ChatHandler passes the correct history to DeepSeekClient
     * 3. After response completion, conversation history is written back to
     *    conversation:{activeSessionId} (not any other key)
     *
     * Strategy: Mock DeepSeekClient.streamResponse to immediately invoke the
     * onChunk callback with a test response, allowing the background thread to
     * detect completion and call updateConversationHistory with the correct key.
     */
    @Property(tries = 100)
    void messagesAlwaysStoredUnderActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String messageContent
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

        // Set up an active session ID
        String activeSessionId = UUID.randomUUID().toString();
        when(conversationSessionService.getActiveSessionId(userId)).thenReturn(activeSessionId);

        // The expected Redis key for this active session
        String expectedKey = "conversation:" + activeSessionId;

        // Return empty history for the active session
        when(valueOperations.get(expectedKey)).thenReturn(null);

        // Mock query rewrite to return original message
        when(queryRewriteService.rewrite(eq(messageContent), anyList())).thenReturn(messageContent);

        // Mock search to return empty results
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // Mock DeepSeekClient to immediately invoke onChunk with a response,
        // so the background thread can detect completion quickly
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("AI回复");
            return null;
        }).when(deepSeekClient).streamResponse(
                anyString(), anyString(), anyList(), any(), any());

        // Create ChatHandler
        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService);

        // Act: process the message
        chatHandler.processMessage(userId, messageContent, wsSession);

        // Wait for the background thread to complete (it waits 3s + 2s internally)
        Thread.sleep(6500);

        // Assert 1 (Req 6.1): conversation history was READ from the active session key
        verify(valueOperations, atLeastOnce()).get(expectedKey);

        // Assert 2 (Req 6.4): conversation history was WRITTEN to the active session key
        // The updateConversationHistory method calls redisTemplate.opsForValue().set(key, json, duration)
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).set(
                keyCaptor.capture(), jsonCaptor.capture(), any(Duration.class));

        // Verify the key used for writing is exactly conversation:{activeSessionId}
        assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);

        // Assert 3: The stored JSON contains the user message and AI response
        String storedJson = jsonCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> storedHistory = objectMapper.readValue(
                storedJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});

        // Should contain at least 2 messages: user message + assistant response
        assertThat(storedHistory).hasSizeGreaterThanOrEqualTo(2);

        // Verify user message is stored with correct content
        Map<String, String> userMsg = storedHistory.get(storedHistory.size() - 2);
        assertThat(userMsg.get("role")).isEqualTo("user");
        assertThat(userMsg.get("content")).isEqualTo(messageContent);

        // Verify assistant response is stored
        Map<String, String> assistantMsg = storedHistory.get(storedHistory.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");
        assertThat(assistantMsg.get("content")).isEqualTo("AI回复");

        // Assert 4 (Req 3.3, 6.2): No writes to any other conversation key
        // All set() calls should only target the active session key
        List<String> allWrittenKeys = keyCaptor.getAllValues();
        for (String writtenKey : allWrittenKeys) {
            assertThat(writtenKey).isEqualTo(expectedKey);
        }
    }

    /**
     * Feature: new-conversation, Property 12: 无活跃会话时自动创建
     *
     * Validates: Requirements 6.3
     *
     * For any user, if they have no active session (getActiveSessionId returns null),
     * when a message is processed through ChatHandler, the system should automatically
     * create a new session and set it as the active session.
     *
     * Strategy: Mock getActiveSessionId to return null, mock createSession to return
     * a new SessionDTO. Verify that createSession is called exactly once and that
     * the newly created session ID is used for reading/writing conversation history.
     */
    @Property(tries = 100)
    void autoCreateSessionWhenNoActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String messageContent
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

        // Key setup: getActiveSessionId returns null (no active session)
        when(conversationSessionService.getActiveSessionId(userId)).thenReturn(null);

        // createSession returns a new session with a generated UUID
        String newSessionId = UUID.randomUUID().toString();
        SessionDTO newSession = new SessionDTO(newSessionId, "新对话", LocalDateTime.now());
        when(conversationSessionService.createSession(userId)).thenReturn(newSession);

        // The expected Redis key for the newly created session
        String expectedKey = "conversation:" + newSessionId;

        // Return empty history for the new session
        when(valueOperations.get(expectedKey)).thenReturn(null);

        // Mock query rewrite to return original message
        when(queryRewriteService.rewrite(eq(messageContent), anyList())).thenReturn(messageContent);

        // Mock search to return empty results
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());

        // Mock DeepSeekClient to immediately invoke onChunk with a response
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("AI回复");
            return null;
        }).when(deepSeekClient).streamResponse(
                anyString(), anyString(), anyList(), any(), any());

        // Create ChatHandler
        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService);

        // Act: process the message
        chatHandler.processMessage(userId, messageContent, wsSession);

        // Wait for the background thread to complete (it waits 3s + 2s internally)
        Thread.sleep(6500);

        // Assert 1 (Req 6.3): createSession was called because no active session existed
        verify(conversationSessionService, times(1)).createSession(userId);

        // Assert 2: The newly created session ID is used for reading conversation history
        verify(valueOperations, atLeastOnce()).get(expectedKey);

        // Assert 3: The newly created session ID is used for writing conversation history
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).set(
                keyCaptor.capture(), jsonCaptor.capture(), any(Duration.class));

        // Verify the key used for writing is conversation:{newSessionId}
        assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);

        // Assert 4: The stored messages contain the user message and AI response
        String storedJson = jsonCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> storedHistory = objectMapper.readValue(
                storedJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});

        assertThat(storedHistory).hasSizeGreaterThanOrEqualTo(2);

        Map<String, String> userMsg = storedHistory.get(storedHistory.size() - 2);
        assertThat(userMsg.get("role")).isEqualTo("user");
        assertThat(userMsg.get("content")).isEqualTo(messageContent);

        Map<String, String> assistantMsg = storedHistory.get(storedHistory.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");
    }
}
