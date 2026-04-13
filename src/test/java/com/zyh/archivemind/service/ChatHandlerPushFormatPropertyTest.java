package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
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
 * Property: WebSocket 推送消息格式正确性
 */
class ChatHandlerPushFormatPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatHandler createChatHandler(AiProperties aiProperties) {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        HybridSearchService searchService = mock(HybridSearchService.class);
        ConversationSessionService conversationSessionService = mock(ConversationSessionService.class);
        LlmRouter llmRouter = mock(LlmRouter.class);
        ToolCallParser toolCallParser = mock(ToolCallParser.class);
        UserLlmPreferenceService preferenceService = mock(UserLlmPreferenceService.class);

        return new ChatHandler(
                redisTemplate, searchService, conversationSessionService,
                llmRouter, toolCallParser, preferenceService, aiProperties);
    }

    @Property(tries = 100)
    void sendThinkingChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        AiProperties aiProperties = new AiProperties();
        ChatHandler chatHandler = createChatHandler(aiProperties);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-id");

        chatHandler.sendThinkingChunk(session, chunkContent);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(messageCaptor.capture());

        String payload = messageCaptor.getValue().getPayload();
        JsonNode json = MAPPER.readTree(payload);

        assertThat(json.get("type").asText()).isEqualTo("thinking");
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        assertThat(json.size()).isEqualTo(2);
    }

    @Property(tries = 100)
    void sendAnswerChunkPushesCorrectJsonFormat(
            @ForAll @StringLength(min = 1, max = 500) String chunkContent
    ) throws Exception {
        AiProperties aiProperties = new AiProperties();
        ChatHandler chatHandler = createChatHandler(aiProperties);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session-id");

        chatHandler.sendAnswerChunk(session, chunkContent);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(messageCaptor.capture());

        String payload = messageCaptor.getValue().getPayload();
        JsonNode json = MAPPER.readTree(payload);

        assertThat(json.get("type").asText()).isEqualTo("answer");
        assertThat(json.get("chunk").asText()).isEqualTo(chunkContent);
        assertThat(json.size()).isEqualTo(2);
    }
}
