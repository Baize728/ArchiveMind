package com.zyh.archivemind.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.entity.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;

@Repository
public class RedisRepository {

    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRepository(StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // ========== 原有方法 ==========

    public String getCurrentConversationId(String userId) {
        return stringRedisTemplate.opsForValue().get("user:" + userId + ":current_conversation");
    }

    public List<Message> getConversationHistory(String conversationId) {
        String json = stringRedisTemplate.opsForValue().get("conversation:" + conversationId);
        try {
            return json == null ? new ArrayList<>() : objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Message.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse conversation history", e);
        }
    }

    public void saveConversationHistory(String conversationId, List<Message> messages) throws JsonProcessingException {
        stringRedisTemplate.opsForValue().set("conversation:" + conversationId, objectMapper.writeValueAsString(messages), Duration.ofDays(7));
    }

    // ========== 2.1 会话列表管理（Sorted Set） ==========

    public void addSessionToUserList(String userId, String sessionId, double score) {
        stringRedisTemplate.opsForZSet().add("user:" + userId + ":sessions", sessionId, score);
    }

    public Set<String> getUserSessionIds(String userId) {
        Set<String> raw = stringRedisTemplate.opsForZSet().reverseRange("user:" + userId + ":sessions", 0, -1);
        return raw == null ? new LinkedHashSet<>() : new LinkedHashSet<>(raw);
    }

    public void removeSessionFromUserList(String userId, String sessionId) {
        stringRedisTemplate.opsForZSet().remove("user:" + userId + ":sessions", sessionId);
    }

    // ========== 2.2 会话元数据管理 ==========

    public void saveSessionMeta(String sessionId, String metaJson) {
        stringRedisTemplate.opsForValue().set("session:" + sessionId + ":meta", metaJson, SESSION_TTL);
    }

    public String getSessionMeta(String sessionId) {
        return stringRedisTemplate.opsForValue().get("session:" + sessionId + ":meta");
    }

    public void deleteSessionMeta(String sessionId) {
        stringRedisTemplate.delete("session:" + sessionId + ":meta");
    }

    // ========== 2.3 活跃会话管理 ==========

    public void setActiveSession(String userId, String sessionId) {
        stringRedisTemplate.opsForValue().set("user:" + userId + ":active_session", sessionId, SESSION_TTL);
    }

    public String getActiveSession(String userId) {
        return stringRedisTemplate.opsForValue().get("user:" + userId + ":active_session");
    }

    public void clearActiveSession(String userId) {
        stringRedisTemplate.delete("user:" + userId + ":active_session");
    }

    // ========== 2.4 Lua 脚本原子操作 ==========

    /**
     * 原子性创建会话：ZADD + SET meta + SET active_session + EXPIRE
     */
    public void createSessionAtomic(String userId, String sessionId, String metaJson, double score, Duration ttl) {
        String script =
            "local sessionsKey = KEYS[1]\n" +
            "local metaKey = KEYS[2]\n" +
            "local activeKey = KEYS[3]\n" +
            "local sessionId = ARGV[1]\n" +
            "local metaJson = ARGV[2]\n" +
            "local score = tonumber(ARGV[3])\n" +
            "local ttlSeconds = tonumber(ARGV[4])\n" +
            "redis.call('ZADD', sessionsKey, score, sessionId)\n" +
            "redis.call('SET', metaKey, metaJson)\n" +
            "redis.call('EXPIRE', metaKey, ttlSeconds)\n" +
            "redis.call('SET', activeKey, sessionId)\n" +
            "redis.call('EXPIRE', activeKey, ttlSeconds)\n" +
            "return 1";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        stringRedisTemplate.execute(redisScript,
                Arrays.asList(
                        "user:" + userId + ":sessions",
                        "session:" + sessionId + ":meta",
                        "user:" + userId + ":active_session"
                ),
                sessionId, metaJson, String.valueOf((long) score), String.valueOf(ttl.getSeconds()));
    }

    /**
     * 原子性删除会话：ZREM + DEL meta + DEL conversation + 条件清除 active_session
     */
    public void deleteSessionAtomic(String userId, String sessionId) {
        String script =
            "local sessionsKey = KEYS[1]\n" +
            "local metaKey = KEYS[2]\n" +
            "local convKey = KEYS[3]\n" +
            "local activeKey = KEYS[4]\n" +
            "local sessionId = ARGV[1]\n" +
            "redis.call('ZREM', sessionsKey, sessionId)\n" +
            "redis.call('DEL', metaKey)\n" +
            "redis.call('DEL', convKey)\n" +
            "local currentActive = redis.call('GET', activeKey)\n" +
            "if currentActive == sessionId then\n" +
            "  redis.call('DEL', activeKey)\n" +
            "end\n" +
            "return 1";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        stringRedisTemplate.execute(redisScript,
                Arrays.asList(
                        "user:" + userId + ":sessions",
                        "session:" + sessionId + ":meta",
                        "conversation:" + sessionId,
                        "user:" + userId + ":active_session"
                ),
                sessionId);
    }

    // ========== 2.5 TTL 刷新 ==========

    /**
     * 刷新会话相关所有键的过期时间为 30 天
     */
    public void refreshSessionKeys(String sessionId, String userId, Duration ttl) {
        stringRedisTemplate.expire("session:" + sessionId + ":meta", ttl);
        stringRedisTemplate.expire("conversation:" + sessionId, ttl);
        stringRedisTemplate.expire("user:" + userId + ":active_session", ttl);
    }
}
