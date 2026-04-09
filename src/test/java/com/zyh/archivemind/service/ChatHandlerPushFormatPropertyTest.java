package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Feature: ai-streaming-thinking-display, Property 2: WebSocket 推送消息格式正确性
 *
 * Validates: Requirements 1.4
 *
 * For any non-empty thinking/answer chunk content, the pushed JSON message
 * should contain the correct type and chunk fields.
 */
class ChatHandlerPushFormatPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Creates a ChatHandler with mocked dependencies.
     * AiProperties is real with thinking.enabled = true by default.
     */
    private ChatHandler createChatHandler(AiProperties aiProperties) {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        HybridSearchService searchService = mock(HybridSearchService.class);
        DeepSeekClient deepSeekClient = mock(DeepSeekClient.class);
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        ConversationSessionService conversationSessionService = mock(ConversationSessionService.class);

        return new ChatHandler(
                redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService, aiProperties);
    }

    /**
     * Feature: ai-streaming-thinking-display, Property 2: WebSocket 推送消息格式正确性
     *
     * Validates: Requirements 1.4
     *
     * For any non-empty string chunk, sendThinkingChunk sends a JSON message
     * with {"type":"thinking","chunk":"<content>"} where the JSON is parseable
     * and contains exactly the right type and chunk values.
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 2: WebSocket 推送消息格式正确性 - thinking chunk")
    void sendThinkingChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        AiProperties aiProperties = new AiProperties();
        // thinking is enabled by default
        ChatHandler chatHandler = createChatHandler(aiProperties);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-id");

        // Act
        chatHandler.sendThinkingChunk(session, chunkContent);

        // Capture the TextMessage sent to the session
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(messageCaptor.capture());

        // Parse the JSON payload
        String payload = messageCaptor.getValue().getPayload();
        JsonNode json = MAPPER.readTree(payload);

        // Assert: JSON contains exactly "type" = "thinking" and "chunk" = chunkContent
        assertThat(json.has("type")).isTrue();
        assertThat(json.get("type").asText()).isEqualTo("thinking");
        assertThat(json.has("chunk")).isTrue();
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        // Ensure no extra unexpected fields
        assertThat(json.size()).isEqualTo(2);
    }

    /**
     * Feature: ai-streaming-thinking-display, Property 2: WebSocket 推送消息格式正确性
     *
     * Validates: Requirements 1.4
     *
     * For any non-empty string chunk, sendAnswerChunk sends a JSON message
     * with {"type":"answer","chunk":"<content>"} where the JSON is parseable
     * and contains exactly the right type and chunk values.
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 2: WebSocket 推送消息格式正确性 - answer chunk")
    void sendAnswerChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        AiProperties aiProperties = new AiProperties();
        ChatHandler chatHandler = createChatHandler(aiProperties);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-id");

        // Act
        chatHandler.sendAnswerChunk(session, chunkContent);

        // Capture the TextMessage sent to the session
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(messageCaptor.capture());

        // Parse the JSON payload
        String payload = messageCaptor.getValue().getPayload();
        JsonNode json = MAPPER.readTree(payload);

        // Assert: JSON contains exactly "type" = "answer" and "chunk" = chunkContent
        assertThat(json.has("type")).isTrue();
        assertThat(json.get("type").asText()).isEqualTo("answer");
        assertThat(json.has("chunk")).isTrue();
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        // Ensure no extra unexpected fields
        assertThat(json.size()).isEqualTo(2);
    }
}
