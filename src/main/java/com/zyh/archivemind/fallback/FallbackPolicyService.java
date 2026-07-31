package com.zyh.archivemind.fallback;

import com.zyh.archivemind.clarify.ClarifyResult;
import com.zyh.archivemind.clarify.ClarifyRuleService;
import com.zyh.archivemind.clarify.SlotBundle;
import com.zyh.archivemind.client.ClarifyLlmClient;
import com.zyh.archivemind.common.DomainAliasMatcher;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.intent.Intent;
import com.zyh.archivemind.intent.IntentResult;
import com.zyh.archivemind.model.SessionState;
import com.zyh.archivemind.trace.FallbackRecorder;
import com.zyh.archivemind.trace.TraceScope;
import com.zyh.archivemind.util.LlmJsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 降级策略统管服务（T1-5，Q12）。
 *
 * 统一入口 execute(stage, ctx) + switch 内部分发到各环节私有方法。
 * 降级逻辑从 IntentReviseService / SlotExtractor.llmFallback / ClarifyAgentService 迁入。
 *
 * 各方法返回类型不同（IntentResult / SlotBundle / ClarifyResult / String），
 * execute 返回 Object，调用方按 stage 强转。
 */
@Service
public class FallbackPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(FallbackPolicyService.class);

    private final AiProperties aiProperties;
    private final DomainAliasMatcher domainAliasMatcher;
    private final ClarifyRuleService clarifyRuleService;
    private final ClarifyLlmClient clarifyLlmClient;

    public FallbackPolicyService(AiProperties aiProperties,
                                 DomainAliasMatcher domainAliasMatcher,
                                 ClarifyRuleService clarifyRuleService,
                                 ClarifyLlmClient clarifyLlmClient) {
        this.aiProperties = aiProperties;
        this.domainAliasMatcher = domainAliasMatcher;
        this.clarifyRuleService = clarifyRuleService;
        this.clarifyLlmClient = clarifyLlmClient;
    }

    /**
     * 统一降级入口。按 stage 分发，返回降级产物（调用方按 stage 强转）。
     */
    public Object execute(String stage, FallbackContext ctx) {
        return switch (stage) {
            case "intent"   -> fallbackIntent(ctx);
            case "slot"     -> fallbackSlot(ctx);
            case "clarify"  -> fallbackClarify(ctx);
            case "chitchat" -> fallbackChitchat(ctx);
            case "answer"   -> fallbackAnswer(ctx);
            default -> throw new IllegalArgumentException("未知降级环节: " + stage);
        };
    }

    // ── intent：从 IntentReviseService.revise 迁入 ──

    private IntentResult fallbackIntent(FallbackContext ctx) {
        IntentResult llmResult = ctx.getLlmIntentResult();
        String userInput = ctx.getUserInput();
        SessionState state = ctx.getSessionState();
        TraceScope scope = ctx.getTraceScope();

        // LLM 完全失败 → 关键词兜底
        if (llmResult == null) {
            Intent forced = matchForcedKeyword(userInput);
            if (forced != null) {
                FallbackRecorder.record(scope, "intent", "keyword", "llm_null", forced.name());
                return IntentResult.keywordFallback(forced);
            }
            FallbackRecorder.record(scope, "intent", "keyword", "llm_null", Intent.AMBIGUOUS.name());
            return IntentResult.keywordFallback(Intent.AMBIGUOUS);
        }

        // 规则一：强制关键词覆盖
        Intent forced = matchForcedKeyword(userInput);
        if (forced != null) {
            FallbackRecorder.record(scope, "intent", "rule", "keyword_override", forced.name());
            return new IntentResult(forced, llmResult.confidence(), "RULE", llmResult.rawReply());
        }

        // 规则二：Q11 升格判定
        if (llmResult.intent() == Intent.AMBIGUOUS
                && state != null
                && state.lastIntent() == Intent.AMBIGUOUS) {
            var domain = domainAliasMatcher.match(userInput);
            if (domain.isPresent()) {
                FallbackRecorder.record(scope, "intent", "rule", "ambiguous_promote", Intent.KNOWLEDGE_QA.name());
                return new IntentResult(Intent.KNOWLEDGE_QA, 0.6, "RULE", llmResult.rawReply());
            }
        }

        // 规则三：低置信度降级
        double threshold = aiProperties.getIntent().getAmbiguousThreshold();
        if (llmResult.confidence() < threshold) {
            FallbackRecorder.record(scope, "intent", "rule", "low_confidence", Intent.AMBIGUOUS.name());
            return new IntentResult(Intent.AMBIGUOUS, llmResult.confidence(), "RULE", llmResult.rawReply());
        }

        // 无矫正规则命中，保留 LLM 结果
        return llmResult;
    }

    // ── slot：从 SlotExtractor.llmFallback 迁入 ──

    private SlotBundle fallbackSlot(FallbackContext ctx) {
        String userInput = ctx.getUserInput();
        TraceScope scope = ctx.getTraceScope();

        if (!aiProperties.getClarify().isEnabled()) {
            FallbackRecorder.record(scope, "slot", "empty", "clarify_disabled", "SlotBundle.empty()");
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
                FallbackRecorder.record(scope, "slot", "empty", "llm_null", "SlotBundle.empty()");
                return SlotBundle.empty();
            }

            JsonNode root = LlmJsonExtractor.parseObject(reply);
            if (root == null) {
                FallbackRecorder.record(scope, "slot", "empty", "parse_fail", "SlotBundle.empty()");
                return SlotBundle.empty();
            }

            return new SlotBundle(
                    root.path("domain").asText(null),
                    root.path("docScope").asText(null),
                    root.path("timeRange").asText(null),
                    root.path("entity").asText(null));
        } catch (Exception e) {
            logger.warn("SlotExtractor LLM fallback 失败: {}", e.getMessage());
            FallbackRecorder.record(scope, "slot", "empty", "llm_exception:" + e.getMessage(), "SlotBundle.empty()");
            return SlotBundle.empty();
        }
    }

    // ── clarify：从 ClarifyAgentService.decide 的 catch/null 分支迁入 ──

    private ClarifyResult fallbackClarify(FallbackContext ctx) {
        boolean withOptions = ctx.getClarifyTurn() >= 1;
        String fallback = clarifyRuleService.fallbackQuestion(ctx.getMissingSlots(), withOptions);
        FallbackRecorder.record(ctx.getTraceScope(), "clarify", "template",
                "llm_null_or_exception", fallback);
        return ClarifyResult.ask(fallback, ctx.getMissingSlots());
    }

    // ── chitchat：从 ChatHandler.handleChitchat 的 null 分支迁入 ──

    private String fallbackChitchat(FallbackContext ctx) {
        String template = aiProperties.getFallback().getChitchatTemplate();
        FallbackRecorder.record(ctx.getTraceScope(), "chitchat", "template", "llm_null", template);
        return template;
    }

    // ── answer：T1-5 新增 ──

    private String fallbackAnswer(FallbackContext ctx) {
        boolean isPartial = ctx.getExistingResponse() != null && !ctx.getExistingResponse().isBlank();
        String template = isPartial
                ? aiProperties.getFallback().getAnswerPartialTemplate()
                : aiProperties.getFallback().getAnswerTemplate();
        String reason = ctx.getError() != null
                ? "llm_stream_error:" + ctx.getError().getMessage()
                : "llm_empty_output";
        FallbackRecorder.record(ctx.getTraceScope(), "answer", "template", reason, template);
        return template;
    }

    // ── matchForcedKeyword / containsAny 从 IntentReviseService 迁入 ──

    private Intent matchForcedKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Intent.AMBIGUOUS;
        }
        Map<String, List<String>> keywords = aiProperties.getIntent().getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        if (containsAny(userInput, keywords.getOrDefault("doc_operation", List.of()))) {
            return Intent.DOC_OPERATION;
        }
        if (containsAny(userInput, keywords.getOrDefault("chitchat", List.of()))) {
            return Intent.CHITCHAT;
        }
        if (containsAny(userInput, keywords.getOrDefault("knowledge_qa", List.of()))) {
            return Intent.KNOWLEDGE_QA;
        }
        return null;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
