package com.zyh.archivemind.clarify;

import java.util.List;

/**
 * 澄清节点结构化输出（T1-2）。
 * 规则层决定 ASK/READY，LLM 只负责把缺失槽位包装成自然追问。
 *
 * @param action       ASK=需追问，READY=可进 RAG
 * @param questionToAsk 追问文案（ASK 时非空，READY 时 null）
 * @param missingSlots 当前仍缺失的槽位名列表
 */
public record ClarifyResult(
        ClarifyAction action,
        String questionToAsk,
        List<String> missingSlots
) {
    public enum ClarifyAction { ASK, READY }

    public static ClarifyResult ready() {
        return new ClarifyResult(ClarifyAction.READY, null, List.of());
    }

    public static ClarifyResult readyWithDisclaimer(String disclaimer) {
        return new ClarifyResult(ClarifyAction.READY, disclaimer, List.of());
    }

    public static ClarifyResult ask(String questionToAsk, List<String> missingSlots) {
        return new ClarifyResult(ClarifyAction.ASK, questionToAsk,
                missingSlots == null ? List.of() : List.copyOf(missingSlots));
    }
}
