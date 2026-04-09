package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatHandler thinkingContent 截断逻辑单元测试
 *
 * 验证 updateConversationHistory 中的 maxPersistLength 截断行为：
 * - 超过 maxPersistLength 的内容被正确截断并追加提示
 * - 未超长的内容不被截断
 * - null/empty thinkingContent 不写入 thinkingContent 字段
 *
 * Requirements: 2.5
 */
@ExtendWith(MockitoExtension.class)
class ChatHandlerTruncationTest {

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
    private AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TRUNCATION_SUFFIX = "\n\n[思考过程内容过长，已截断]";

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        // 设置较小的 maxPersistLength 便于测试
        aiProperties.getThinking().setMaxPersistLength(50);

        chatHandler = new ChatHandler(redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService, aiProperties);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(session.getId()).thenReturn("truncation-test-session");
        lenient().when(session.isOpen()).thenReturn(true);
    }

    private void setupCommonMocks() throws Exception {
        when(conversationSessionService.getActiveSessionId(anyString())).thenReturn("conv-truncation");
        when(valueOperations.get("conversation:conv-truncation")).thenReturn(null);
        when(queryRewriteService.rewrite(anyString(), anyList())).thenReturn("rewritten");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("超过 maxPersistLength 的 thinkingContent 应被截断并追加截断提示")
    void thinkingContentExceedingMaxLengthShouldBeTruncated() throws Exception {
        setupCommonMocks();

        // 生成超过 maxPersistLength(50) 的思考内容
        String longThinking = "A".repeat(80);

        doAnswer(invocation -> {
            Consumer<String> onThinkingChunk = invocation.getArgument(3);
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onThinkingChunk.accept(longThinking);
            onAnswerChunk.accept("回答内容");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("userTrunc", "测试问题", session);

        // 捕获 Redis set 调用
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("conversation:conv-truncation"), jsonCaptor.capture(), any(Duration.class));

        // 解析存储的 JSON
        List<Map<String, String>> history = objectMapper.readValue(
                jsonCaptor.getValue(), new TypeReference<>() {});

        Map<String, String> assistantMsg = history.get(history.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");

        String storedThinking = assistantMsg.get("thinkingContent");
        assertThat(storedThinking).isNotNull();
        // 截断后应为前50个字符 + 截断提示
        assertThat(storedThinking).isEqualTo(longThinking.substring(0, 50) + TRUNCATION_SUFFIX);
        // 验证截断后的总长度
        assertThat(storedThinking).hasSize(50 + TRUNCATION_SUFFIX.length());
    }

    @Test
    @DisplayName("未超过 maxPersistLength 的 thinkingContent 应原样保存不截断")
    void thinkingContentWithinMaxLengthShouldNotBeTruncated() throws Exception {
        setupCommonMocks();

        // 生成未超过 maxPersistLength(50) 的思考内容
        String shortThinking = "B".repeat(30);

        doAnswer(invocation -> {
            Consumer<String> onThinkingChunk = invocation.getArgument(3);
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onThinkingChunk.accept(shortThinking);
            onAnswerChunk.accept("回答内容");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("userShort", "测试问题", session);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("conversation:conv-truncation"), jsonCaptor.capture(), any(Duration.class));

        List<Map<String, String>> history = objectMapper.readValue(
                jsonCaptor.getValue(), new TypeReference<>() {});

        Map<String, String> assistantMsg = history.get(history.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");
        // 未超长，应原样保存
        assertThat(assistantMsg.get("thinkingContent")).isEqualTo(shortThinking);
    }

    @Test
    @DisplayName("null/empty thinkingContent 不应在存储的 JSON 中包含 thinkingContent 字段")
    void nullOrEmptyThinkingContentShouldNotBePersisted() throws Exception {
        setupCommonMocks();

        // 不发送任何 thinking chunk，只发送 answer
        doAnswer(invocation -> {
            Consumer<String> onAnswerChunk = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            onAnswerChunk.accept("纯回答内容");
            onComplete.run();
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), anyList(),
                any(), any(), any(), any());

        chatHandler.processMessage("userNoThink", "测试问题", session);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("conversation:conv-truncation"), jsonCaptor.capture(), any(Duration.class));

        List<Map<String, String>> history = objectMapper.readValue(
                jsonCaptor.getValue(), new TypeReference<>() {});

        Map<String, String> assistantMsg = history.get(history.size() - 1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");
        assertThat(assistantMsg.get("content")).isEqualTo("纯回答内容");
        // thinkingContent 字段不应存在
        assertThat(assistantMsg).doesNotContainKey("thinkingContent");
    }
}
