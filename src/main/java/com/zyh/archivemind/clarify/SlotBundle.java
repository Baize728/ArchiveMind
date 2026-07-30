package com.zyh.archivemind.clarify;

/**
 * 4 维 RAG 检索约束槽位（T1-2）。
 *
 * 与 Diet 的差异：Diet 的 SlotBundle 是 7 维 List<String>（餐饮域），
 * ArchiveMind 是 4 维 String（RAG 检索约束），单值非多值——
 * RAG 检索通常是"一个业务域 + 一个文档 + 一个时间 + 一个对象"。
 *
 * @param domain    业务域：finance/hr/legal/it
 * @param docScope  文档范围：合同模板/报销制度/...
 * @param timeRange 时间范围：2024/latest/...
 * @param entity    具体对象：差旅报销/年假申请/...
 */
public record SlotBundle(
        String domain,
        String docScope,
        String timeRange,
        String entity
) {
    public static SlotBundle empty() {
        return new SlotBundle(null, null, null, null);
    }

    public boolean isEmpty() {
        return domain == null && docScope == null && timeRange == null && entity == null;
    }
}
