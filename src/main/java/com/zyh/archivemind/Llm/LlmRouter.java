package com.zyh.archivemind.Llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 路由器
 * 负责管理所有已注册的 LLM Provider，并根据 ID 或默认配置进行路由
 */
@Component
public class LlmRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmRouter.class);

    /** 已注册的 Provider 映射：providerId -> LlmProvider */
    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    /** 默认 Provider ID */
    private String defaultProviderId;

    /**
     * 构造函数：自动注入所有 LlmProvider Bean 并注册
     * Spring 会自动收集容器中所有实现了 LlmProvider 接口的 Bean
     */
    public LlmRouter(List<LlmProvider> providerList, LlmProperties llmProperties) {
        // 注册所有 Provider
        for (LlmProvider provider : providerList) {
            providers.put(provider.getProviderId(), provider);
            logger.info("注册 LLM Provider: {}, 支持工具调用: {}",
                    provider.getProviderId(), provider.supportsToolCalling());
        }

        // 设置默认 Provider
        this.defaultProviderId = llmProperties.getDefaultProvider();
        if (this.defaultProviderId == null || !providers.containsKey(this.defaultProviderId)) {
            // 如果配置的默认 Provider 不存在，使用第一个可用的
            if (!providers.isEmpty()) {
                this.defaultProviderId = providers.keySet().iterator().next();
                logger.warn("配置的默认 Provider 不可用，使用: {}", this.defaultProviderId);
            }
        }
        logger.info("LlmRouter 初始化完成，共 {} 个 Provider，默认: {}",
                providers.size(), this.defaultProviderId);
    }

    /**
     * 根据 ID 获取 Provider
     * @param providerId Provider 唯一标识
     * @return 对应的 LlmProvider 实例
     * @throws IllegalArgumentException 如果 Provider 不存在
     */
    public LlmProvider getProvider(String providerId) {
        LlmProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "未找到 LLM Provider: " + providerId + "，可用: " + providers.keySet());
        }
        return provider;
    }

    /**
     * 获取默认 Provider
     * @return 默认的 LlmProvider 实例
     */
    public LlmProvider getDefaultProvider() {
        return getProvider(defaultProviderId);
    }

    /**
     * 列出所有已注册的 Provider ID
     */
    public List<String> listProviders() {
        return List.copyOf(providers.keySet());
    }

    /**
     * 获取默认 Provider ID
     */
    public String getDefaultProviderId() {
        return defaultProviderId;
    }

    /**
     * 运行时切换默认 Provider
     * @param providerId 新的默认 Provider ID
     */
    public void setDefaultProviderId(String providerId) {
        if (!providers.containsKey(providerId)) {
            throw new IllegalArgumentException("Provider 不存在: " + providerId);
        }
        this.defaultProviderId = providerId;
        logger.info("默认 LLM Provider 已切换为: {}", providerId);
    }

    /**
     * 获取所有 Provider 的详细信息
     */
    public Map<String, LlmProvider> getAllProviders() {
        return Collections.unmodifiableMap(providers);
    }
}