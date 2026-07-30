package com.zyh.archivemind.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * SessionState Redis 读写服务（T1-2，Q3/Q15）。
 *
 * Key: archivemind:session:{conversationId}:state
 * TTL: 30min（与对话历史一致）
 */
@Service
public class SessionStateService {

    private static final Logger logger = LoggerFactory.getLogger(SessionStateService.class);
    private static final String KEY_PREFIX = "archivemind:session:";
    private static final String KEY_SUFFIX = ":state";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionStateService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public SessionState get(String conversationId) {
        String key = key(conversationId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return SessionState.fresh();
            }
            return objectMapper.readValue(json, SessionState.class);
        } catch (Exception e) {
            logger.warn("读取 SessionState 失败, conversationId={}: {}", conversationId, e.getMessage());
            return SessionState.fresh();
        }
    }

    public void update(String conversationId, SessionState state) {
        String key = key(conversationId);
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception e) {
            logger.warn("写入 SessionState 失败, conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId + KEY_SUFFIX;
    }
}
