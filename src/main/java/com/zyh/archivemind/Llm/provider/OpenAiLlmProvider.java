package com.zyh.archivemind.Llm.provider;

import com.zyh.archivemind.Llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenAI LLM Provider 实现
 * 支持 GPT-4o、GPT-4-turbo 等模型
 * 由于 OpenAI API 格式与 DeepSeek 兼容，继承 OpenAI 兼容基类
 */
@Component
@ConditionalOnProperty(prefix = "llm.providers.openai", name = "enabled", havingValue = "true")
public class OpenAiLlmProvider extends AbstractOpenAiCompatibleProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiLlmProvider.class);
    private static final String PROVIDER_ID = "openai";

    public OpenAiLlmProvider(LlmProperties llmProperties) {
        super(PROVIDER_ID, llmProperties);
        logger.info("OpenAiLlmProvider 初始化完成");
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }
}
