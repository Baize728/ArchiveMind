package com.zyh.archivemind.clarify;

import com.zyh.archivemind.client.ClarifyLlmClient;
import com.zyh.archivemind.common.DomainAliasMatcher;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.util.LlmJsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 槽位提取器（T1-2，Q2/Q8）。
 *
 * 规则层优先（~90% 覆盖），4 槽位全 null 时 fallback LLM（~10%）。
 *
 * 规则层总流程：
 * 1. domain    = DomainAliasMatcher.match(query)         // 同义词包含
 * 2. docScope  = DocScopePatternExtractor.extract(query) // 正则+句式
 * 3. timeRange = TimeRangeRegexParser.parse(query)       // 分层正则
 * 4. entity    = EntityNounPhraseExtractor.extract(query) // HanLP 分词
 * 5. 若 4 槽位全 null → fallback LLM
 *
 * 覆盖抽取语义（Q19）：抽到即覆盖旧值，抽不到才保留。
 */
@Service
public class SlotExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SlotExtractor.class);

    private final DomainAliasMatcher domainMatcher;
    private final DocScopePatternExtractor docScopeExtractor;
    private final TimeRangeRegexParser timeRangeParser;
    private final EntityNounPhraseExtractor entityExtractor;
    private final ClarifyLlmClient clarifyLlmClient;
    private final AiProperties aiProperties;

    public SlotExtractor(DomainAliasMatcher domainMatcher,
                         DocScopePatternExtractor docScopeExtractor,
                         TimeRangeRegexParser timeRangeParser,
                         EntityNounPhraseExtractor entityExtractor,
                         ClarifyLlmClient clarifyLlmClient,
                         AiProperties aiProperties) {
        this.domainMatcher = domainMatcher;
        this.docScopeExtractor = docScopeExtractor;
        this.timeRangeParser = timeRangeParser;
        this.entityExtractor = entityExtractor;
        this.clarifyLlmClient = clarifyLlmClient;
        this.aiProperties = aiProperties;
    }

    /**
     * 从用户输入提取 4 维槽位。
     * 规则层优先，4 槽位全 null 时 fallback LLM。
     */
    public SlotBundle extract(String userInput) {
        // 1. 规则层
        String domain = domainMatcher.match(userInput).orElse(null);
        String docScope = docScopeExtractor.extract(userInput);
        String timeRange = timeRangeParser.parse(userInput);
        String entity = entityExtractor.extract(userInput);

        // 2. 4 槽位全 null → LLM fallback
        if (domain == null && docScope == null && timeRange == null && entity == null) {
            logger.debug("规则层 4 槽位全 null，fallback LLM: {}", userInput);
            return llmFallback(userInput);
        }

        return new SlotBundle(domain, docScope, timeRange, entity);
    }

    /**
     * LLM fallback：调 ClarifyLlmClient 抽取槽位，约束输出 JSON。
     * 失败时返回空 SlotBundle。
     */
    private SlotBundle llmFallback(String userInput) {
        if (!aiProperties.getClarify().isEnabled()) {
            return SlotBundle.empty();
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content",
                    "你是ArchiveMind知识库的槽位提取模块。请从用户输入中提取以下 4 个检索约束槽位：\n"
                            + "- domain: 业务域，候选值 finance/hr/legal/it\n"
                            + "- docScope: 文档范围，如 合同模板/报销制度\n"
                            + "- timeRange: 时间范围，如 2024/latest\n"
                            + "- entity: 具体查询对象，如 差旅报销/年假申请\n"
                            + "只输出 JSON，格式：{\"domain\":\"finance\",\"docScope\":null,\"timeRange\":null,\"entity\":\"差旅报销\"}\n"
                            + "无法映射的字段输出 null，不要创造候选值之外的 domain。"));
            messages.add(Map.of("role", "user", "content", userInput));

            String reply = clarifyLlmClient.chatSync(messages, 256, 0.0);
            if (reply == null || reply.isBlank()) {
                return SlotBundle.empty();
            }

            JsonNode root = LlmJsonExtractor.parseObject(reply);
            if (root == null) {
                return SlotBundle.empty();
            }

            return new SlotBundle(
                    root.path("domain").asText(null),
                    root.path("docScope").asText(null),
                    root.path("timeRange").asText(null),
                    root.path("entity").asText(null));
        } catch (Exception e) {
            logger.warn("SlotExtractor LLM fallback 失败: {}", e.getMessage());
            return SlotBundle.empty();
        }
    }
}
