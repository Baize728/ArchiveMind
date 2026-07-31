package com.zyh.archivemind.clarify;

import com.zyh.archivemind.common.DomainAliasMatcher;
import com.zyh.archivemind.fallback.FallbackContext;
import com.zyh.archivemind.fallback.FallbackPolicyService;
import com.zyh.archivemind.trace.TraceScope;
import org.springframework.stereotype.Service;

/**
 * 槽位提取器（T1-2，Q2/Q8；T1-5 Q12 改造）。
 *
 * 规则层优先（~90% 覆盖），4 槽位全 null 时 fallback LLM（~10%）。
 *
 * T1-5 改动（Q12）：LLM fallback 逻辑迁入 FallbackPolicyService.fallbackSlot。
 * 本类只保留规则层 4 Matcher，4-null 时调 fallbackPolicyService.execute("slot", ctx)。
 *
 * 规则层总流程：
 * 1. domain    = DomainAliasMatcher.match(query)
 * 2. docScope  = DocScopePatternExtractor.extract(query)
 * 3. timeRange = TimeRangeRegexParser.parse(query)
 * 4. entity    = EntityNounPhraseExtractor.extract(query)
 * 5. 若 4 槽位全 null → FallbackPolicyService.execute("slot", ctx)
 *
 * 覆盖抽取语义（Q19）：抽到即覆盖旧值，抽不到才保留。
 */
@Service
public class SlotExtractor {

    private final DomainAliasMatcher domainMatcher;
    private final DocScopePatternExtractor docScopeExtractor;
    private final TimeRangeRegexParser timeRangeParser;
    private final EntityNounPhraseExtractor entityExtractor;
    private final FallbackPolicyService fallbackPolicyService;

    public SlotExtractor(DomainAliasMatcher domainMatcher,
                         DocScopePatternExtractor docScopeExtractor,
                         TimeRangeRegexParser timeRangeParser,
                         EntityNounPhraseExtractor entityExtractor,
                         FallbackPolicyService fallbackPolicyService) {
        this.domainMatcher = domainMatcher;
        this.docScopeExtractor = docScopeExtractor;
        this.timeRangeParser = timeRangeParser;
        this.entityExtractor = entityExtractor;
        this.fallbackPolicyService = fallbackPolicyService;
    }

    /**
     * 从用户输入提取 4 维槽位。
     * 规则层优先，4 槽位全 null 时调 FallbackPolicyService 走 LLM fallback。
     */
    public SlotBundle extract(String userInput, TraceScope traceScope) {
        // 1. 规则层
        String domain = domainMatcher.match(userInput).orElse(null);
        String docScope = docScopeExtractor.extract(userInput);
        String timeRange = timeRangeParser.parse(userInput);
        String entity = entityExtractor.extract(userInput);

        // 2. 4 槽位全 null → FallbackPolicyService（LLM fallback + 失败兜底）
        if (domain == null && docScope == null && timeRange == null && entity == null) {
            FallbackContext ctx = FallbackContext.builder()
                    .stage("slot")
                    .traceScope(traceScope)
                    .userInput(userInput)
                    .build();
            return (SlotBundle) fallbackPolicyService.execute("slot", ctx);
        }

        return new SlotBundle(domain, docScope, timeRange, entity);
    }
}
