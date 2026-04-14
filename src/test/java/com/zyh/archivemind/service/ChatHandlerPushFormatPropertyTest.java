package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.agent.AgentCallback;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property: WebSocket 推送消息格式正确性
 * 通过 processMessage 触发，验证 AgentCallback 桥接后推送的 JSON 格式
 */
class ChatHandlerPushFormatPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Property(tries = 100)
    void thinkingChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ConversationSessionService sessionService = mock(ConversationSessionService.class);
        UserLlmPreferenceService preferenceService = mock(UserLlmPreferenceService.class);
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(wsSession.getId()).thenReturn("test-session-id");
        when(wsSession.isOpen()).thenReturn(true);
        when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        when(sessionService.getActiveSessionId(anyString())).thenReturn("conv-1");
        when(valueOperations.get(anyString())).thenReturn(null);

        // AgentExecutor 回调 onThinkingChunk + onComplete
        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onThinkingChunk(chunkContent);
            cb.onTextChunk("reply");
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        AiProperties aiProperties = new AiProperties();
        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, sessionService, preferenceService, agentExecutor, aiProperties);

        // Act
        chatHandler.processMessage("user1", "hello", wsSession);

        // Assert: 找到 thinking 类型的消息并验证格式
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, atLeastOnce()).sendMessage(captor.capture());

        List<TextMessage> allMessages = captor.getAllValues();
        TextMessage thinkingMsg = allMessages.stream()
                .filter(m -> m.getPayload().contains("\"type\":\"thinking\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 thinking 类型消息"));

        JsonNode json = MAPPER.readTree(thinkingMsg.getPayload());
        assertThat(json.get("type").asText()).isEqualTo("thinking");
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        assertThat(json.size()).isEqualTo(2);
    }

    @Property(tries = 100)
    void answerChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        // Arrange
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ConversationSessionService sessionService = mock(ConversationSessionService.class);
        UserLlmPreferenceService preferenceService = mock(UserLlmPreferenceService.class);
        AgentExecutor agentExecutor = mock(AgentExecutor.class);
        LlmProvider llmProvider = mock(LlmProvider.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(wsSession.getId()).thenReturn("test-session-id");
        when(wsSession.isOpen()).thenReturn(true);
        when(preferenceService.getProviderForUser(anyString())).thenReturn(llmProvider);
        when(sessionService.getActiveSessionId(anyString())).thenReturn("conv-2");
        when(valueOperations.get(anyString())).thenReturn(null);

        // AgentExecutor 回调 onTextChunk + onComplete
        doAnswer(inv -> {
            AgentCallback cb = inv.getArgument(3);
            cb.onTextChunk(chunkContent);
            cb.onComplete();
            return null;
        }).when(agentExecutor).execute(any(), any(), any(), any());

        AiProperties aiProperties = new AiProperties();
        ChatHandler chatHandler = new ChatHandler(
                redisTemplate, sessionService, preferenceService, agentExecutor, aiProperties);

        // Act
        chatHandler.processMessage("user2", "hello", wsSession);

        // Assert: 找到 answer 类型的消息并验证格式
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(wsSession, atLeastOnce()).sendMessage(captor.capture());

        List<TextMessage> allMessages = captor.getAllValues();
        TextMessage answerMsg = allMessages.stream()
                .filter(m -> m.getPayload().contains("\"type\":\"answer\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 answer 类型消息"));

        JsonNode json = MAPPER.readTree(answerMsg.getPayload());
        assertThat(json.get("type").asText()).isEqualTo("answer");
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        assertThat(json.size()).isEqualTo(2);
    }
}
