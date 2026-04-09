package com.zyh.archivemind.Llm.provider;

import com.zyh.archivemind.Llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ollama 本地模型 LLM Provider 实现
 * 支持 deepseek-r1:7b、llama3 等本地模型
 */
@Component
@ConditionalOnProperty(prefix = "llm.providers.ollama", name = "enabled", havingValue = "true")
public class OllamaLlmProvider extends AbstractOpenAiCompatibleProvider {

    private static final Logger logger = LoggerFactory.getLogger(OllamaLlmProvider.class);
    private static final String PROVIDER_ID = "ollama";

    public OllamaLlmProvider(LlmProperties llmProperties) {
        super(PROVIDER_ID, llmProperties);
        logger.info("OllamaLlmProvider 初始化完成，模型: {}",
                llmProperties.getProviders().get(PROVIDER_ID).getModel());
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }
}
