package com.zyh.archivemind.intent;

import com.zyh.archivemind.common.DomainAliasMatcher;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.model.SessionState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别规则矫正层（第 2 层）。
 * 借鉴 Diet-Agent §8.1 IntentReviseService.revise。
 *
 * 对 LLM 输出做规则矫正：
 * - LLM 返回 null → 兜底 AMBIGUOUS(0.2, KEYWORD)
 * - 命中强制关键词 → 覆盖意图（source=RULE）
 * - 低置信度（<0.4）→ 降级 AMBIGUOUS（source=RULE）
 * - Q11 升格：lastIntent==AMBIGUOUS + 命中 domain 别名 → 升格 KNOWLEDGE_QA（T1-2）
 *
 * T1-2 改动（Q16）：revise 签名加 SessionState，读 state.lastIntent 做升格判定。
 * IntentAgentService 保持无状态（不接收 state）。
 */
@Service
public class IntentReviseService {

    private final AiProperties aiProperties;
    private final DomainAliasMatcher domainAliasMatcher;

    public IntentReviseService(AiProperties aiProperties, DomainAliasMatcher domainAliasMatcher) {
        this.aiProperties = aiProperties;
        this.domainAliasMatcher = domainAliasMatcher;
    }

    /**
     * 规则矫正 LLM 输出（T1-2 带 state 版本，Q16）。
     *
     * @param llmResult   LLM 层返回的结果（可为 null）
     * @param userInput   用户输入（用于关键词匹配和升格判定）
     * @param state       会话状态（读 lastIntent 做 Q11 升格判定；可为 null）
     * @return 矫正后的 IntentResult
     */
    public IntentResult revise(IntentResult llmResult, String userInput, SessionState state) {
        // LLM 完全失败时，先尝试关键词兜底；无关键词命中才返回 AMBIGUOUS
        if (llmResult == null) {
            Intent forced = matchForcedKeyword(userInput);
            if (forced != null) {
                return IntentResult.keywordFallback(forced);
            }
            return IntentResult.keywordFallback(Intent.AMBIGUOUS);
        }

        // 规则一：命中强制关键词 → 覆盖（source=RULE）
        Intent forced = matchForcedKeyword(userInput);
        if (forced != null) {
            return new IntentResult(forced, llmResult.confidence(), "RULE", llmResult.rawReply());
        }

        // 规则二：Q11 升格判定（T1-2，Q23 修正：只用 domain 别名）
        // 上一轮 AMBIGUOUS + 本轮 LLM 仍判 AMBIGUOUS + 命中 domain 别名 → 升格 KNOWLEDGE_QA
        if (llmResult.intent() == Intent.AMBIGUOUS
                && state != null
                && state.lastIntent() == Intent.AMBIGUOUS) {
            var domain = domainAliasMatcher.match(userInput);
            if (domain.isPresent()) {
                return new IntentResult(Intent.KNOWLEDGE_QA, 0.6, "RULE", llmResult.rawReply());
            }
        }

        // 规则三：低置信度 → 降级 AMBIGUOUS
        double threshold = aiProperties.getIntent().getAmbiguousThreshold();
        if (llmResult.confidence() < threshold) {
            return new IntentResult(Intent.AMBIGUOUS, llmResult.confidence(), "RULE", llmResult.rawReply());
        }

        // 无矫正规则命中，保留 LLM 结果
        return llmResult;
    }

    /**
     * 兼容 T1-1 旧签名（无 state）——内部传 null，不触发升格判定。
     */
    public IntentResult revise(IntentResult llmResult, String userInput) {
        return revise(llmResult, userInput, null);
    }

    /**
     * 强制关键词匹配：按优先级 DOC_OPERATION > CHITCHAT > KNOWLEDGE_QA。
     * 命中则返回对应 Intent，未命中返回 null。
     */
    private Intent matchForcedKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Intent.AMBIGUOUS;
        }
        Map<String, List<String>> keywords = aiProperties.getIntent().getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        // 按优先级匹配
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

    /** 判断 text 是否包含 keywords 中任一子串 */
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
