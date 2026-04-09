package com.zyh.archivemind.service;

import com.zyh.archivemind.client.DeepSeekClient;
import com.zyh.archivemind.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatHandler.cleanupStaleBuilders() 定时清理逻辑单元测试
 *
 * 验证超时 session 数据被正确清理，未超时 session 数据不被清理。
 *
 * Validates: Requirements 5.3
 */
@ExtendWith(MockitoExtension.class)
class ChatHandlerCleanupTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private HybridSearchService searchService;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private ConversationSessionService conversationSessionService;

    private ChatHandler chatHandler;

    // 通过反射获取的内部 Map 引用
    private Map<String, Long> sessionStartTimes;
    private Map<String, StringBuilder> responseBuilders;
    private Map<String, StringBuilder> thinkingBuilders;
    private Map<String, Boolean> stopFlags;
    private Map<String, String> sessionConversationIds;
    private Map<String, String> sessionUserIds;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        AiProperties aiProperties = new AiProperties();
        chatHandler = new ChatHandler(redisTemplate, searchService, deepSeekClient,
                queryRewriteService, conversationSessionService, aiProperties);

        // 通过反射获取内部 Map 引用
        sessionStartTimes = getField("sessionStartTimes");
        responseBuilders = getField("responseBuilders");
        thinkingBuilders = getField("thinkingBuilders");
        stopFlags = getField("stopFlags");
        sessionConversationIds = getField("sessionConversationIds");
        sessionUserIds = getField("sessionUserIds");
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String fieldName) throws Exception {
        Field field = ChatHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(chatHandler);
    }

    /**
     * 填充指定 sessionId 的所有内部 Map 数据
     */
    private void populateSessionData(String sessionId, long startTime) {
        sessionStartTimes.put(sessionId, startTime);
        responseBuilders.put(sessionId, new StringBuilder("response-" + sessionId));
        thinkingBuilders.put(sessionId, new StringBuilder("thinking-" + sessionId));
        stopFlags.put(sessionId, false);
        sessionConversationIds.put(sessionId, "conv-" + sessionId);
        sessionUserIds.put(sessionId, "user-" + sessionId);
    }

    @Test
    @DisplayName("超过10分钟的 session 数据应被清理")
    void staleSessionShouldBeCleanedUp() {
        // 设置一个 11 分钟前开始的 session（超过 10 分钟阈值）
        long elevenMinutesAgo = System.currentTimeMillis() - 660_000;
        populateSessionData("stale-session", elevenMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        // 所有 Map 中该 session 的数据都应被移除
        assertThat(sessionStartTimes).doesNotContainKey("stale-session");
        assertThat(responseBuilders).doesNotContainKey("stale-session");
        assertThat(thinkingBuilders).doesNotContainKey("stale-session");
        assertThat(stopFlags).doesNotContainKey("stale-session");
        assertThat(sessionConversationIds).doesNotContainKey("stale-session");
        assertThat(sessionUserIds).doesNotContainKey("stale-session");
    }

    @Test
    @DisplayName("未超过10分钟的 session 数据不应被清理")
    void freshSessionShouldNotBeCleanedUp() {
        // 设置一个 5 分钟前开始的 session（未超过 10 分钟阈值）
        long fiveMinutesAgo = System.currentTimeMillis() - 300_000;
        populateSessionData("fresh-session", fiveMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        // 所有 Map 中该 session 的数据都应保留
        assertThat(sessionStartTimes).containsKey("fresh-session");
        assertThat(responseBuilders).containsKey("fresh-session");
        assertThat(thinkingBuilders).containsKey("fresh-session");
        assertThat(stopFlags).containsKey("fresh-session");
        assertThat(sessionConversationIds).containsKey("fresh-session");
        assertThat(sessionUserIds).containsKey("fresh-session");
    }

    @Test
    @DisplayName("混合场景：仅清理超时 session，保留未超时 session")
    void mixedSessionsShouldBeHandledCorrectly() {
        long elevenMinutesAgo = System.currentTimeMillis() - 660_000;
        long twoMinutesAgo = System.currentTimeMillis() - 120_000;

        populateSessionData("stale-1", elevenMinutesAgo);
        populateSessionData("fresh-1", twoMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        // stale session 应被清理
        assertThat(sessionStartTimes).doesNotContainKey("stale-1");
        assertThat(responseBuilders).doesNotContainKey("stale-1");
        assertThat(thinkingBuilders).doesNotContainKey("stale-1");
        assertThat(stopFlags).doesNotContainKey("stale-1");
        assertThat(sessionConversationIds).doesNotContainKey("stale-1");
        assertThat(sessionUserIds).doesNotContainKey("stale-1");

        // fresh session 应保留
        assertThat(sessionStartTimes).containsKey("fresh-1");
        assertThat(responseBuilders).containsKey("fresh-1");
        assertThat(thinkingBuilders).containsKey("fresh-1");
        assertThat(stopFlags).containsKey("fresh-1");
        assertThat(sessionConversationIds).containsKey("fresh-1");
        assertThat(sessionUserIds).containsKey("fresh-1");
    }

    @Test
    @DisplayName("恰好10分钟的 session 不应被清理（边界条件）")
    void exactlyTenMinuteSessionShouldNotBeCleanedUp() {
        // 恰好 10 分钟 = 600000ms，cleanupStaleBuilders 判断条件是 > 600000
        long exactlyTenMinutesAgo = System.currentTimeMillis() - 600_000;
        populateSessionData("boundary-session", exactlyTenMinutesAgo);

        chatHandler.cleanupStaleBuilders();

        // 恰好 10 分钟不应被清理（条件是严格大于）
        assertThat(sessionStartTimes).containsKey("boundary-session");
        assertThat(responseBuilders).containsKey("boundary-session");
        assertThat(thinkingBuilders).containsKey("boundary-session");
    }

    @Test
    @DisplayName("无 session 数据时调用 cleanupStaleBuilders 不应抛异常")
    void emptyMapsShouldNotCauseError() {
        // 所有 Map 为空时调用不应抛异常
        chatHandler.cleanupStaleBuilders();

        assertThat(sessionStartTimes).isEmpty();
        assertThat(responseBuilders).isEmpty();
        assertThat(thinkingBuilders).isEmpty();
    }
}
