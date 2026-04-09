package com.zyh.archivemind.Llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户 LLM 偏好服务
 * 每个用户可以独立选择使用哪个 LLM Provider，互不影响
 */
@Service
public class UserLlmPreferenceService {

    private static final Logger logger = LoggerFactory.getLogger(UserLlmPreferenceService.class);
    private static final String KEY_PREFIX = "user:llm_provider:";
    private static final Duration TTL = Duration.ofDays(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final LlmRouter llmRouter;

    public UserLlmPreferenceService(RedisTemplate<String, String> redisTemplate, LlmRouter llmRouter) {
        this.redisTemplate = redisTemplate;
        this.llmRouter = llmRouter;
    }

    /**
     * 获取用户选择的 Provider，未设置则返回全局默认
     */
    public LlmProvider getProviderForUser(String userId) {
        String providerId = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (providerId != null) {
            try {
                return llmRouter.getProvider(providerId);
            } catch (IllegalArgumentException e) {
                // Provider 已被禁用，清除偏好，回退到默认
                logger.warn("用户 {} 保存的 Provider [{}] 不可用，回退到默认", userId, providerId);
                redisTemplate.delete(KEY_PREFIX + userId);
            }
        }
        return llmRouter.getDefaultProvider();
    }

    /**
     * 设置用户的 Provider 偏好
     */
    public void setProviderForUser(String userId, String providerId) {
        // 校验 Provider 存在
        llmRouter.getProvider(providerId);
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, providerId, TTL);
        logger.info("用户 {} 切换 LLM Provider 为: {}", userId, providerId);
    }

    /**
     * 获取用户当前使用的 Provider ID（用于前端展示）
     */
    public String getProviderIdForUser(String userId) {
        String providerId = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        return providerId != null ? providerId : llmRouter.getDefaultProviderId();
    }
}
