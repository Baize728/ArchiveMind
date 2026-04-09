package com.zyh.archivemind.Llm.provider;

import com.zyh.archivemind.Llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 通义千问 LLM Provider 实现
 * 使用 DashScope OpenAI 兼容接口，支持 qwen-plus、qwen-max 等模型
 */
@Component
@ConditionalOnProperty(prefix = "llm.providers.qwen", name = "enabled", havingValue = "true")
public class QwenLlmProvider extends AbstractOpenAiCompatibleProvider {

    private static final Logger logger = LoggerFactory.getLogger(QwenLlmProvider.class);
    private static final String PROVIDER_ID = "qwen";

    public QwenLlmProvider(LlmProperties llmProperties) {
        super(PROVIDER_ID, llmProperties);
        LlmProperties.ProviderConfig config = llmProperties.getProviders().get(PROVIDER_ID);
        logger.info("QwenLlmProvider 初始化完成，模型: {}", config.getModel());
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }
}
