package com.zyh.archivemind.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/**
 * 短期记忆
 * 使用 Redis 缓存工具调用结果，避免重复调用相同参数的工具
 */
@Component
public class ShortTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermMemory.class);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "skill:result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ShortTermMemory(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void cache(String userId, String toolName, Map<String, Object> params, String result) {
        String key = buildKey(userId, toolName, params);
        try {
            redisTemplate.opsForValue().set(key, result, TTL);
            logger.debug("缓存工具结果: key={}", key);
        } catch (Exception e) {
            logger.warn("缓存工具结果失败: {}", e.getMessage());
        }
    }

    public String recall(String userId, String toolName, Map<String, Object> params) {
        String key = buildKey(userId, toolName, params);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                logger.debug("命中工具结果缓存: key={}", key);
            }
            return cached;
        } catch (Exception e) {
            logger.warn("查询缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildKey(String userId, String toolName, Map<String, Object> params) {
        return KEY_PREFIX + userId + ":" + toolName + ":" + hashParams(params);
    }

    String hashParams(Map<String, Object> params) {
        try {
            String json = objectMapper.writeValueAsString(params);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "default";
        }
    }
}
