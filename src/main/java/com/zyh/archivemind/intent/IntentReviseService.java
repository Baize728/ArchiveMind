package com.zyh.archivemind.intent;

import com.zyh.archivemind.config.AiProperties;
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
 *
 * 与 Diet 的差异：Diet 的 revise 依赖 SessionState（判断有无上轮推荐）。
 * ArchiveMind 一期不引入 SessionState，规则只看 userInput + confidence。
 */
@Service
public class IntentReviseService {

    private final AiProperties aiProperties;

    public IntentReviseService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * 规则矫正 LLM 输出。
     *
     * @param llmResult   LLM 层返回的结果（可为 null）
     * @param userInput   用户原始输入（用于关键词匹配）
     * @return 矫正后的 IntentResult
     */
    public IntentResult revise(IntentResult llmResult, String userInput) {
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

        // 规则二：低置信度 → 降级 AMBIGUOUS
        double threshold = aiProperties.getIntent().getAmbiguousThreshold();
        if (llmResult.confidence() < threshold) {
            return new IntentResult(Intent.AMBIGUOUS, llmResult.confidence(), "RULE", llmResult.rawReply());
        }

        // 无矫正规则命中，保留 LLM 结果
        return llmResult;
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
