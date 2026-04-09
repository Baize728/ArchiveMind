package com.zyh.archivemind.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.entity.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class RedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private ObjectMapper objectMapper;
    private RedisRepository redisRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        redisRepository = new RedisRepository(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    // ========== 2.1 会话列表管理（Sorted Set）Tests ==========

    @Nested
    @DisplayName("会话列表管理 - Sorted Set 操作 (Req 7.1)")
    class SessionListTests {

        @Test
        @DisplayName("addSessionToUserList 应使用正确的键和分数调用 ZADD")
        void addSessionToUserList_shouldCallZAddWithCorrectKeyAndScore() {
            redisRepository.addSessionToUserList("user1", "session-abc", 1700000000000.0);

            verify(zSetOperations).add("user:user1:sessions", "session-abc", 1700000000000.0);
        }

        @Test
        @DisplayName("getUserSessionIds 应返回按分数降序排列的会话ID集合")
        void getUserSessionIds_shouldReturnReverseOrderedSet() {
            Set<String> raw = new LinkedHashSet<>(Arrays.asList("s3", "s2", "s1"));
            when(zSetOperations.reverseRange("user:user1:sessions", 0, -1)).thenReturn(raw);

            Set<String> result = redisRepository.getUserSessionIds("user1");

            assertEquals(3, result.size());
            Iterator<String> it = result.iterator();
            assertEquals("s3", it.next());
            assertEquals("s2", it.next());
            assertEquals("s1", it.next());
        }

        @Test
        @DisplayName("getUserSessionIds 当无数据时应返回空集合")
        void getUserSessionIds_shouldReturnEmptySetWhenNull() {
            when(zSetOperations.reverseRange("user:user1:sessions", 0, -1)).thenReturn(null);

            Set<String> result = redisRepository.getUserSessionIds("user1");

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("removeSessionFromUserList 应调用 ZREM 移除指定会话")
        void removeSessionFromUserList_shouldCallZRem() {
            redisRepository.removeSessionFromUserList("user1", "session-abc");

            verify(zSetOperations).remove("user:user1:sessions", "session-abc");
        }
    }

    // ========== 2.2 会话元数据管理 Tests ==========

    @Nested
    @DisplayName("会话元数据管理 (Req 7.2)")
    class SessionMetaTests {

        @Test
        @DisplayName("saveSessionMeta 应使用 30 天 TTL 存储元数据 JSON")
        void saveSessionMeta_shouldSetWithTTL() {
            String metaJson = "{\"sessionId\":\"s1\",\"userId\":\"u1\",\"title\":\"新对话\"}";

            redisRepository.saveSessionMeta("s1", metaJson);

            verify(valueOperations).set("session:s1:meta", metaJson, Duration.ofDays(30));
        }

        @Test
        @DisplayName("getSessionMeta 应返回存储的元数据 JSON")
        void getSessionMeta_shouldReturnStoredJson() {
            String expected = "{\"sessionId\":\"s1\"}";
            when(valueOperations.get("session:s1:meta")).thenReturn(expected);

            String result = redisRepository.getSessionMeta("s1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("getSessionMeta 当键不存在时应返回 null")
        void getSessionMeta_shouldReturnNullWhenNotFound() {
            when(valueOperations.get("session:s1:meta")).thenReturn(null);

            assertNull(redisRepository.getSessionMeta("s1"));
        }

        @Test
        @DisplayName("deleteSessionMeta 应删除元数据键")
        void deleteSessionMeta_shouldDeleteKey() {
            redisRepository.deleteSessionMeta("s1");

            verify(redisTemplate).delete("session:s1:meta");
        }
    }

    // ========== 2.3 活跃会话管理 Tests ==========

    @Nested
    @DisplayName("活跃会话管理 (Req 7.3, 7.4)")
    class ActiveSessionTests {

        @Test
        @DisplayName("setActiveSession 应使用 30 天 TTL 设置活跃会话")
        void setActiveSession_shouldSetWithTTL() {
            redisRepository.setActiveSession("user1", "session-abc");

            verify(valueOperations).set("user:user1:active_session", "session-abc", Duration.ofDays(30));
        }

        @Test
        @DisplayName("getActiveSession 应返回当前活跃会话ID")
        void getActiveSession_shouldReturnSessionId() {
            when(valueOperations.get("user:user1:active_session")).thenReturn("session-abc");

            assertEquals("session-abc", redisRepository.getActiveSession("user1"));
        }

        @Test
        @DisplayName("getActiveSession 当无活跃会话时应返回 null")
        void getActiveSession_shouldReturnNullWhenNone() {
            when(valueOperations.get("user:user1:active_session")).thenReturn(null);

            assertNull(redisRepository.getActiveSession("user1"));
        }

        @Test
        @DisplayName("clearActiveSession 应删除活跃会话键")
        void clearActiveSession_shouldDeleteKey() {
            redisRepository.clearActiveSession("user1");

            verify(redisTemplate).delete("user:user1:active_session");
        }
    }

    // ========== 2.4 Lua 脚本原子操作 Tests ==========

    @Nested
    @DisplayName("Lua 脚本原子操作 (Req 7.1, 7.2, 7.4)")
    class AtomicOperationTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("createSessionAtomic 应使用正确的键和参数执行 Lua 脚本")
        void createSessionAtomic_shouldExecuteLuaWithCorrectKeysAndArgs() {
            doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());

            redisRepository.createSessionAtomic("user1", "s1", "{\"meta\":true}", 1700000000000.0, Duration.ofDays(30));

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);

            verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                    argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

            List<String> keys = keysCaptor.getValue();
            assertEquals(3, keys.size());
            assertEquals("user:user1:sessions", keys.get(0));
            assertEquals("session:s1:meta", keys.get(1));
            assertEquals("user:user1:active_session", keys.get(2));

            List<Object> args = argsCaptor.getAllValues();
            assertEquals("s1", args.get(0));
            assertEquals("{\"meta\":true}", args.get(1));
            assertEquals("1700000000000", args.get(2));
            assertEquals(String.valueOf(Duration.ofDays(30).getSeconds()), args.get(3));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("deleteSessionAtomic 应使用正确的 4 个键和 sessionId 参数执行 Lua 脚本")
        void deleteSessionAtomic_shouldExecuteLuaWithCorrectKeysAndArgs() {
            doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any());

            redisRepository.deleteSessionAtomic("user1", "s1");

            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);

            verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

            List<String> keys = keysCaptor.getValue();
            assertEquals(4, keys.size());
            assertEquals("user:user1:sessions", keys.get(0));
            assertEquals("session:s1:meta", keys.get(1));
            assertEquals("conversation:s1", keys.get(2));
            assertEquals("user:user1:active_session", keys.get(3));

            assertEquals("s1", argsCaptor.getValue());
        }
    }

    // ========== 2.5 TTL 刷新 Tests ==========

    @Nested
    @DisplayName("TTL 刷新 (Req 7.4, 7.5)")
    class TTLRefreshTests {

        @Test
        @DisplayName("refreshSessionKeys 应刷新所有三个相关键的过期时间")
        void refreshSessionKeys_shouldExpireAllThreeKeys() {
            Duration ttl = Duration.ofDays(30);

            redisRepository.refreshSessionKeys("s1", "user1", ttl);

            verify(redisTemplate).expire("session:s1:meta", ttl);
            verify(redisTemplate).expire("conversation:s1", ttl);
            verify(redisTemplate).expire("user:user1:active_session", ttl);
        }

        @Test
        @DisplayName("refreshSessionKeys 应使用传入的 TTL 值而非硬编码")
        void refreshSessionKeys_shouldUseProvidedTTL() {
            Duration customTtl = Duration.ofDays(7);

            redisRepository.refreshSessionKeys("s1", "user1", customTtl);

            verify(redisTemplate).expire("session:s1:meta", customTtl);
            verify(redisTemplate).expire("conversation:s1", customTtl);
            verify(redisTemplate).expire("user:user1:active_session", customTtl);
        }
    }

    // ========== 原有方法兼容性 Tests ==========

    @Nested
    @DisplayName("消息历史存储兼容性 (Req 7.3)")
    class ConversationHistoryTests {

        @Test
        @DisplayName("getConversationHistory 应使用 conversation:{sessionId} 键格式")
        void getConversationHistory_shouldUseCorrectKeyFormat() {
            when(valueOperations.get("conversation:session-abc")).thenReturn(null);

            List<Message> result = redisRepository.getConversationHistory("session-abc");

            assertNotNull(result);
            assertTrue(result.isEmpty());
            verify(valueOperations).get("conversation:session-abc");
        }

        @Test
        @DisplayName("getConversationHistory 应正确反序列化消息列表")
        void getConversationHistory_shouldDeserializeMessages() {
            String json = "[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":\"hi\"}]";
            when(valueOperations.get("conversation:s1")).thenReturn(json);

            List<Message> result = redisRepository.getConversationHistory("s1");

            assertEquals(2, result.size());
            assertEquals("user", result.get(0).getRole());
            assertEquals("hello", result.get(0).getContent());
            assertEquals("assistant", result.get(1).getRole());
            assertEquals("hi", result.get(1).getContent());
        }

        @Test
        @DisplayName("saveConversationHistory 应序列化并存储消息列表")
        void saveConversationHistory_shouldSerializeAndStore() throws JsonProcessingException {
            List<Message> messages = List.of(new Message("user", "test"));

            redisRepository.saveConversationHistory("s1", messages);

            verify(valueOperations).set(eq("conversation:s1"), anyString(), eq(Duration.ofDays(7)));
        }
    }
}
