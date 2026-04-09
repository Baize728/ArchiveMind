package com.zyh.archivemind.Llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多 LLM 配置属性
 * 对应 application.yml 中的 llm.* 配置
 */
@Component
@ConfigurationProperties(prefix = "llm")
@Data
public class LlmProperties {

    /** 默认使用的 LLM 提供商 ID */
    private String defaultProvider = "deepseek";

    /** 各 Provider 的配置映射 */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /** 全局生成参数（各 Provider 可覆盖） */
    private GenerationConfig generation = new GenerationConfig();

    @Data
    public static class ProviderConfig {
        /** 是否启用 */
        private boolean enabled = true;
        /** API 地址 */
        private String apiUrl;
        /** API Key */
        private String apiKey;
        /** 默认模型 */
        private String model;
        /** 是否支持工具调用 */
        private boolean supportsToolCalling = false;
    }

    @Data
    public static class GenerationConfig {
        private Double temperature = 0.3;
        private Integer maxTokens = 2000;
        private Double topP = 0.9;
    }
}