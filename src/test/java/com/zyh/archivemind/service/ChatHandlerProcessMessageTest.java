package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.dto.SessionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatHandler.processMessage 单元测试
 *
 * 验证回调模式下的 thinking/answer 推送、completion 通知、历史持久化和 stopFlags 行为。
 *
 * Requirements: 1.4, 1.5, 2.5
 */
@ExtendWith(MockitoExtension.class)
class ChatHandlerProcessMessageTest {

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
        chatHandler = new ChatHandler(redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService, aiProperties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("test-session-id");
        lenient().when(session.isOpen()).thenReturn(true);
    }

    /**
     * 公共 mock 设置：模拟会话获取、查询改写、搜索返回空结果。
     * 调用方通过 doAnswer 自行控制 deepSeekClient.streamResponse 的行为。
     */
    private void setupCommonMocks(String userId) throws Exception {
        when(conversationSessionService.getActiveSessionId(userId)).thenReturn("conv-test");
        when(valueOperations.get("conversation:conv-test")).thenReturn(null);
        when(queryRewriteService.rewrite(anyString(), anyList())).thenReturn("rewritten");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("onThinkingChunk 回调应累积思考内容并推送 thinking 类型消息")
    void onThinkingChunkShouldAccumulateAndPushThinkingMessage() throws Exception {
        setupCommonMocks("user1");

        // 捕获 streamResponse 的回调参数，模拟调用 onThinkingChunk
        doAnswer(invocation -> {
            Consumer<String> onThinkingChunk = invocation.getArgument(3);
            Runnable onComplete = invocation.getArgument(5);
            onThinkingChunk.accept("思考步骤1");
            onThinkingChunk.accept("思考步骤2");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("user1", "测试问题", session);

        // 验证 session.sendMessage 被调用（thinking chunks + completion notification）
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(3)).sendMessage(messageCaptor.capture());

        List<TextMessage> messages = messageCaptor.getAllValues();

        // 第一条：thinking chunk "思考步骤1"
        JsonNode msg1 = objectMapper.readTree(messages.get(0).getPayload());
        assertThat(msg1.get("type").asText()).isEqualTo("thinking");
        assertThat(msg1.get("chunk").asText()).isEqualTo("思考步骤1");

        // 第二条：thinking chunk "思考步骤2"
        JsonNode msg2 = objectMapper.readTree(messages.get(1).getPayload());
        assertThat(msg2.get("type").asText()).isEqualTo("thinking");
        assertThat(msg2.get("chunk").asText()).isEqualTo("思考步骤2");
    }

    @Test
    @DisplayName("onAnswerChunk 回调应累积回答内容并推送 answer 类型消息")
    void onAnswerChunkShouldAccumulateAndPushAnswerMessage() throws Exception {
        setupCommonMocks("user2");

        doAnswer(invocation -> {
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onAnswerChunk.accept("回答片段A");
            onAnswerChunk.accept("回答片段B");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("user2", "测试问题", session);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(3)).sendMessage(messageCaptor.capture());

        List<TextMessage> messages = messageCaptor.getAllValues();

        // 第一条：answer chunk "回答片段A"
        JsonNode msg1 = objectMapper.readTree(messages.get(0).getPayload());
        assertThat(msg1.get("type").asText()).isEqualTo("answer");
        assertThat(msg1.get("chunk").asText()).isEqualTo("回答片段A");

        // 第二条：answer chunk "回答片段B"
        JsonNode msg2 = objectMapper.readTree(messages.get(1).getPayload());
        assertThat(msg2.get("type").asText()).isEqualTo("answer");
        assertThat(msg2.get("chunk").asText()).isEqualTo("回答片段B");
    }

    @Test
    @DisplayName("onComplete 回调应触发 completion 通知和历史持久化")
    void onCompleteShouldSendCompletionAndPersistHistory() throws Exception {
        setupCommonMocks("user3");

        doAnswer(invocation -> {
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onAnswerChunk.accept("完整回答");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("user3", "测试问题", session);

        // 验证 completion 通知被发送
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(2)).sendMessage(messageCaptor.capture());

        List<TextMessage> messages = messageCaptor.getAllValues();
        // 最后一条消息应该是 completion 通知
        JsonNode lastMsg = objectMapper.readTree(messages.get(messages.size() - 1).getPayload());
        assertThat(lastMsg.get("type").asText()).isEqualTo("completion");
        assertThat(lastMsg.get("status").asText()).isEqualTo("finished");

        // 验证历史持久化：updateConversationHistory 通过 Redis set 调用
        verify(valueOperations).set(eq("conversation:conv-test"), anyString(), any());
    }

    @Test
    @DisplayName("stopFlags 生效时不应推送 thinking 和 answer 消息")
    void stopFlagsShouldPreventMessagePush() throws Exception {
        setupCommonMocks("user4");

        doAnswer(invocation -> {
            Consumer<String> onThinkingChunk = invocation.getArgument(3);
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);

            // 先发一条 thinking，然后触发 stopResponse，再发后续消息
            onThinkingChunk.accept("第一条思考");

            // 模拟用户触发停止
            chatHandler.stopResponse("user4", session);

            // 这些消息应该被 stopFlags 拦截
            onThinkingChunk.accept("被拦截的思考");
            onAnswerChunk.accept("被拦截的回答");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("user4", "测试问题", session);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeast(1)).sendMessage(messageCaptor.capture());

        List<TextMessage> messages = messageCaptor.getAllValues();

        // 统计 thinking 和 answer 类型的消息数量
        long thinkingCount = 0;
        long answerCount = 0;
        for (TextMessage msg : messages) {
            JsonNode json = objectMapper.readTree(msg.getPayload());
            if (json.has("type")) {
                String type = json.get("type").asText();
                if ("thinking".equals(type)) thinkingCount++;
                if ("answer".equals(type)) answerCount++;
            }
        }

        // 只有第一条 thinking 应该被推送，后续的 thinking 和 answer 都应被拦截
        assertThat(thinkingCount).isEqualTo(1);
        assertThat(answerCount).isEqualTo(0);
    }
}
