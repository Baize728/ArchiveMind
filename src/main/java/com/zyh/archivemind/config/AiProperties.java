package com.zyh.archivemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 相关配置，目前仅保留 Query Rewriting 配置。
 * Prompt 和 Generation 配置已迁移至 LlmProperties。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Rewrite rewrite = new Rewrite();
    private Thinking thinking = new Thinking();

    @Data
    public static class Rewrite {
        /** 是否启用 Query Rewriting */
        private boolean enabled = true;
        /** 改写用 LLM API 地址 */
        private String apiUrl;
        /** 改写用 LLM API Key */
        private String apiKey;
        /** 改写用 LLM 模型名称 */
        private String model;
        /** 改写 system prompt */
        private String systemPrompt = "你是一个查询改写助手。根据多轮对话历史，将用户最新的问题改写为一个语义完整、可独立理解的检索查询。"
                + "要求：1) 补全省略的主语和上下文；2) 保留用户的核心意图；3) 只输出改写后的查询，不要输出任何解释。"
                + "如果当前问题已经语义完整，直接原样输出即可。";
        /** 历史对话最大轮数（一轮 = 一问一答） */
        private int maxHistoryRounds = 3;
        /** 同步调用超时时间（秒） */
        private int timeoutSeconds = 15;
    }

    @Data
    public static class Prompt {
        /** System prompt 规则 */
        private String rules = "你是ArchiveMind知识助手，须遵守：\n"
                + "1. 仅用简体中文作答。\n"
                + "2. 回答需先给结论，再给论据。\n"
                + "3. 如引用参考信息，请在句末加 (来源#编号: 文件名)。\n"
                + "4. 若无足够信息，请回答\"暂无相关信息\"并说明原因。\n"
                + "5. 当问题需要查阅资料时，请主动调用可用的工具。";
        /** 参考资料起始标记 */
        private String refStart = "<<REF>>";
        /** 参考资料结束标记 */
        private String refEnd = "<<END>>";
        /** 无检索结果时的提示文本 */
        private String noResultText = "（本轮无检索结果）";
    }

    @Data
    public static class Thinking {
        /** 是否推送思考过程到前端 */
        private boolean enabled = true;
        /** thinkingContent 持久化最大字符数，超出截断 */
        private int maxPersistLength = 20000;
    }
}
