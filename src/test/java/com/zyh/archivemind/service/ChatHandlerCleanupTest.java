package com.zyh.archivemind.service;

import com.zyh.archivemind.Llm.*;
import com.zyh.archivemind.agent.AgentExecutor;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.trace.TraceCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatHandler.cleanupStaleBuilders() 定时清理逻辑单元测试
 */
@ExtendWith(MockitoExtension.class)
class ChatHandlerCleanupTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ConversationSessionService conversationSessionService;
    @Mock private UserLlmPreferenceService preferenceService;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TraceCollector traceCollector;
    @Mock private com.zyh.archivemind.intent.IntentRouter intentRouter;
    @Mock private com.zyh.archivemind.client.IntentLlmClient intentLlmClient;

    private ChatHandler chatHandler;

    private Map<String, Long> sessionStartTimes;
    private Map<String, StringBuilder> responseBuilders;
    private Map<String, StringBuilder> thinkingBuilders;
    private Map<String, Boolean> stopFlags;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        lenient().when(queryRewriteService.rewrite(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        com.zyh.archivemind.clarify.SessionStateService sessionStateService =
                mock(com.zyh.archivemind.clarify.SessionStateService.class);
        lenient().when(sessionStateService.get(any()))
                .thenReturn(com.zyh.archivemind.model.SessionState.fresh());

        chatHandler = new ChatHandler(redisTemplate, conversationSessionService,
                preferenceService, agentExecutor, new AiProperties(), traceCollector,
                intentRouter, intentLlmClient,
                queryRewriteService,
                sessionStateService,
                mock(com.zyh.archivemind.clarify.SlotExtractor.class),
                mock(com.zyh.archivemind.clarify.ClarifyRuleService.class),
                mock(com.zyh.archivemind.clarify.ClarifyAgentService.class));

        // 默认意图为 KNOWLEDGE_QA，走 AgentExecutor 路径（三参数版本）
        lenient().when(intentRouter.route(any(), any(), any()))
                .thenReturn(new com.zyh.archivemind.intent.IntentResult(
                        com.zyh.archivemind.intent.Intent.KNOWLEDGE_QA, 0.9, "LLM", ""));

        sessionStartTimes = getField("sessionStartTimes");
        responseBuilders = getField("responseBuilders");
        thinkingBuilders = getField("thinkingBuilders");
        stopFlags = getField("stopFlags");
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String fieldName) throws Exception {
        Field field = ChatHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(chatHandler);
    }

    private void populateSessionData(String sessionId, long startTime) {
        sessionStartTimes.put(sessionId, startTime);
        responseBuilders.put(sessionId, new StringBuilder("response-" + sessionId));
        thinkingBuilders.put(sessionId, new StringBuilder("thinking-" + sessionId));
        stopFlags.put(sessionId, false);
    }

    @Test
    @DisplayName("超过10分钟的 session 数据应被清理")
    void staleSessionShouldBeCleanedUp() {
        long elevenMinutesAgo = System.currentTimeMillis() - 660_000;
        populateSessionData("stale-session", elevenMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        assertThat(sessionStartTimes).doesNotContainKey("stale-session");
        assertThat(responseBuilders).doesNotContainKey("stale-session");
        assertThat(thinkingBuilders).doesNotContainKey("stale-session");
        assertThat(stopFlags).doesNotContainKey("stale-session");
    }

    @Test
    @DisplayName("未超过10分钟的 session 数据不应被清理")
    void freshSessionShouldNotBeCleanedUp() {
        long fiveMinutesAgo = System.currentTimeMillis() - 300_000;
        populateSessionData("fresh-session", fiveMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        assertThat(sessionStartTimes).containsKey("fresh-session");
        assertThat(responseBuilders).containsKey("fresh-session");
        assertThat(thinkingBuilders).containsKey("fresh-session");
        assertThat(stopFlags).containsKey("fresh-session");
    }

    @Test
    @DisplayName("混合场景：仅清理超时 session，保留未超时 session")
    void mixedSessionsShouldBeHandledCorrectly() {
        long elevenMinutesAgo = System.currentTimeMillis() - 660_000;
        long twoMinutesAgo = System.currentTimeMillis() - 120_000;

        populateSessionData("stale-1", elevenMinutesAgo);
        populateSessionData("fresh-1", twoMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        assertThat(sessionStartTimes).doesNotContainKey("stale-1");
        assertThat(responseBuilders).doesNotContainKey("stale-1");
        assertThat(sessionStartTimes).containsKey("fresh-1");
        assertThat(responseBuilders).containsKey("fresh-1");
    }

    @Test
    @DisplayName("无 session 数据时调用 cleanupStaleBuilders 不应抛异常")
    void emptyMapsShouldNotCauseError() {
        chatHandler.cleanupStaleBuilders();
        assertThat(sessionStartTimes).isEmpty();
    }
}
