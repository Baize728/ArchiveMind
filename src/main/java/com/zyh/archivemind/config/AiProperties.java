package com.zyh.archivemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private Intent intent = new Intent();

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

    @Data
    public static class Intent {
        /** 是否启用意图识别 */
        private boolean enabled = true;
        /** 意图识别用 LLM API 地址 */
        private String apiUrl;
        /** 意图识别用 LLM API Key */
        private String apiKey;
        /** 意图识别用 LLM 模型名称 */
        private String model;
        /** 同步调用超时时间（秒） */
        private int timeoutSeconds = 10;
        /** 低置信度降级 AMBIGUOUS 的阈值 */
        private double ambiguousThreshold = 0.4;
        /** AMBIGUOUS 时返回的固定提示文案 */
        private String ambiguousReply = "您的问题我不太确定想查什么，能否补充一下（例如：哪个业务域/哪份文档/什么时间）？";
        /** 意图识别 system prompt */
        private String systemPrompt = "你是ArchiveMind知识库的意图识别模块。请将用户输入分类到以下四类意图之一：\n"
                + "- KNOWLEDGE_QA：用户想从知识库查询信息，回答\"是什么/为什么/怎么做\"类问题。例：\"年假怎么申请\"\"报销标准是什么\"\n"
                + "- CHITCHAT：用户打招呼、客套、寒暄，没有信息检索需求。例：\"你好\"\"谢谢\"\"你是谁\"\n"
                + "- DOC_OPERATION：用户想对文档实体做操作（上传、归档、删除、移动、重命名），而非查文档内容。例：\"把这份合同归档\"\"上传员工手册\"\n"
                + "- AMBIGUOUS：用户有查询意向但信息明显不足，无法定位到具体文档或业务域。例：\"那个政策\"\"帮我查一下规定\"\n"
                + "只输出 JSON，格式为：{\"intent\":\"意图枚举名\",\"confidence\":0.9}\n"
                + "不要寒暄，不要回答用户问题，不要输出任何解释。";
        /** 关键词表，intent 名 -> 关键词列表 */
        private Map<String, List<String>> keywords = new HashMap<>();
    }
}
