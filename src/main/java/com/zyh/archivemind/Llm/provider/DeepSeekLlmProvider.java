package com.zyh.archivemind.Llm.provider;

import com.zyh.archivemind.Llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DeepSeek LLM Provider 实现
 * 继承 AbstractOpenAiCompatibleProvider，复用 OpenAI 兼容接口逻辑
 */
@Component
@ConditionalOnProperty(prefix = "llm.providers.deepseek", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeepSeekLlmProvider extends AbstractOpenAiCompatibleProvider {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekLlmProvider.class);
    private static final String PROVIDER_ID = "deepseek";

    public DeepSeekLlmProvider(LlmProperties llmProperties) {
        super(PROVIDER_ID, llmProperties);
        LlmProperties.ProviderConfig config = llmProperties.getProviders().get(PROVIDER_ID);
        logger.info("DeepSeekLlmProvider 初始化完成，API URL: {}, 模型: {}",
                config.getApiUrl(), config.getModel());
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }
}
