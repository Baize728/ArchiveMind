package com.zyh.archivemind.intent;

/**
 * 意图识别结果。
 *
 * 与 Diet 的差异：Diet 的 IntentResult 带 SlotBundle slots，ArchiveMind 不带——
 * 槽位归 T1-2 澄清层，避免一层混入两件事（简单优先）。
 * 新增 source 字段便于 Trace 与 T1-4 评测统计 intentAccuracy 时区分来源。
 *
 * @param intent     最终意图
 * @param confidence 置信度（LLM 层由模型给出，规则/关键词层固定 0.2）
 * @param source     来源："LLM" | "RULE" | "KEYWORD"
 * @param rawReply   LLM 原始返回文本，用于 Trace/调试；规则/关键词层为 ""
 */
public record IntentResult(
        Intent intent,
        double confidence,
        String source,
        String rawReply
) {
    /** 关键词兜底用：固定低置信度 0.2 */
    public static IntentResult keywordFallback(Intent intent) {
        return new IntentResult(intent, 0.2, "KEYWORD", "");
    }
}
