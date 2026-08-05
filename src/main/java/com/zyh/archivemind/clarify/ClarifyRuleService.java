package com.zyh.archivemind.clarify;

import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.model.SessionState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 澄清规则服务（T1-2）。
 *
 * 职责：
 * 1. 计算缺失的必填槽位（Q12 条件必填）
 * 2. 过滤已问且用户未答的字段（Q13）
 * 3. 按优先级 [domain, entity, docScope, timeRange] 排序
 *
 * 借鉴 Diet ClarifyRuleService.missingSlots，但槽位定义和必填逻辑不同。
 */
@Service
public class ClarifyRuleService {

    private static final List<String> PRIORITY = List.of("domain", "entity", "docScope", "timeRange");

    private final AiProperties aiProperties;

    public ClarifyRuleService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * 计算缺失的必填槽位，按优先级排序，过滤已问未答的。
     *
     * @return 缺失槽位列表（空列表=READY）
     */
    public List<String> missingSlots(SlotBundle slots, SessionState state) {
        return missingSlots(null, slots, state);
    }

    /**
     * 根据当前查询计算缺失槽位。
     * 完整的概念/事实问题不要求补充业务域或实体，避免正常问题被澄清层拦截。
     */
    public List<String> missingSlots(String query, SlotBundle slots, SessionState state) {
        // 1. 计算原始缺失（Q12 逻辑）
        List<String> raw = computeRawMissing(query, slots);

        // 2. 过滤已问且用户未答的（Q13）
        List<String> asked = state != null && state.lastAskedFields() != null
                ? state.lastAskedFields() : List.of();
        List<String> freshMissing = raw.stream()
                .filter(slot -> !asked.contains(slot))
                .toList();

        // 3. 该问的都问过 → 强制 READY
        if (freshMissing.isEmpty()) {
            return List.of();
        }

        // 4. 按优先级排序
        return freshMissing.stream()
                .sorted(Comparator.comparingInt(s -> PRIORITY.indexOf(s)))
                .toList();
    }

    /**
     * Q12 条件必填判定。
     * domain 条件必填（阈值 3）；entity 在 docScope 也缺失时必填。
     */
    private List<String> computeRawMissing(String query, SlotBundle slots) {
        List<String> missing = new ArrayList<>();
        int threshold = aiProperties.getClarify().getDomainRequiredOtherScoreThreshold();

        // 已经明确提出概念/原理/定义类问题时，业务域只是可选过滤条件。
        if (isSelfContainedKnowledgeQuestion(query, slots)) {
            return missing;
        }

        // domain 条件必填
        if (slots.domain() == null) {
            int otherScore = (slots.entity() != null ? 2 : 0)
                    + (slots.docScope() != null ? 1 : 0)
                    + (slots.timeRange() != null ? 1 : 0);
            if (otherScore < threshold) {
                missing.add("domain");
            }
        }

        // entity 在 docScope 也缺失时必填（至少要有一个具体对象）
        if (slots.entity() == null && slots.docScope() == null) {
            missing.add("entity");
        }

        // timeRange/docScope 永不强制追问
        return missing;
    }

    private boolean isSelfContainedKnowledgeQuestion(String query, SlotBundle slots) {
        if (query == null || query.isBlank()) {
            return false;
        }

        String normalized = query.trim();
        boolean conceptualQuestion = normalized.matches(
                ".*(什么是|是什么|何为|概念|原理|含义|区别|为什么).*");
        if (!conceptualQuestion) {
            return false;
        }

        if (slots != null && (slots.entity() != null || slots.docScope() != null)) {
            return true;
        }

        String topic = normalized
                .replaceFirst("^(什么是|是什么|何为|为什么)\\s*", "")
                .replaceAll("[\\s?？。！!，,、：:]", "");
        return topic.length() >= 2;
    }

    /**
     * LLM 澄清失败或返回空时的模板追问文案（Q7 兜底）。
     *
     * @param missingSlots 缺失槽位
     * @param withOptions  第 2 轮追问是否给候选选项
     */
    public String fallbackQuestion(List<String> missingSlots, boolean withOptions) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "请补充更多信息以便我为您检索。";
        }
        String firstMissing = missingSlots.get(0);
        return switch (firstMissing) {
            case "domain" -> withOptions
                    ? "请问是哪个业务域？例如：财务、人事、法务、技术？"
                    : "请问您指的是哪个业务域？";
            case "entity" -> withOptions
                    ? "具体是哪个方面？例如：差旅报销、年假申请、数据加密？"
                    : "能否具体说明您想查询的内容？";
            case "docScope" -> "请问是哪份文档？";
            case "timeRange" -> "请问需要哪个时间段的内容？";
            default -> "请补充更多信息以便我为您检索。";
        };
    }
}
