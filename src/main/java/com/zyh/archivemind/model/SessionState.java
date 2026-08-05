package com.zyh.archivemind.model;

import com.zyh.archivemind.clarify.SlotBundle;
import com.zyh.archivemind.intent.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮累积状态（T1-2，Q3/Q15）。
 *
 * Redis key: archivemind:session:{conversationId}:state，TTL 30min。
 * 用 conversationId 作为 key（非 WebSocket session.getId()），跨重连存活。
 *
 * @param slots            累积的检索约束槽位
 * @param clarifyTurn      当前澄清轮次计数（Q18，ASK 时 +1，上限 2）
 * @param lastIntent       上一轮识别的意图（Q11/Q21 升格判定用）
 * @param lastAskedFields  已问且用户未答的字段列表（Q13，补充即移除）
 * @param pendingQuery     当前等待用户补充信息的原始问题
 * @param pendingIntent    当前等待补充问题的原始意图
 */
public record SessionState(
        SlotBundle slots,
        int clarifyTurn,
        Intent lastIntent,
        List<String> lastAskedFields,
        String pendingQuery,
        Intent pendingIntent
) {
    public static SessionState fresh() {
        return new SessionState(SlotBundle.empty(), 0, null, List.of(), null, null);
    }

    /** 写入本轮意图（Q21，每轮 route 返回后统一写入） */
    public SessionState withLastIntent(Intent intent) {
        return new SessionState(this.slots, this.clarifyTurn, intent, this.lastAskedFields,
                this.pendingQuery, this.pendingIntent);
    }

    /** 更新槽位 */
    public SessionState withSlots(SlotBundle slots) {
        return new SessionState(slots, this.clarifyTurn, this.lastIntent, this.lastAskedFields,
                this.pendingQuery, this.pendingIntent);
    }

    /** 保存当前等待用户补充信息的问题 */
    public SessionState withPendingClarification(String query, Intent intent) {
        return new SessionState(this.slots, this.clarifyTurn, this.lastIntent, this.lastAskedFields,
                query, intent);
    }

    public boolean hasPendingClarification() {
        return pendingQuery != null && !pendingQuery.isBlank() && pendingIntent != null;
    }

    /** READY 后清理：清所有 slots + clarifyTurn + lastAskedFields，保留 lastIntent（Q19/Q22） */
    public SessionState onReady() {
        return new SessionState(SlotBundle.empty(), 0, this.lastIntent, List.of(), null, null);
    }

    /** 换话题重置：清 clarifyTurn + lastAskedFields，不清 slots（Q18） */
    public SessionState resetClarify() {
        return new SessionState(this.slots, 0, this.lastIntent, List.of(), null, null);
    }

    /** 新话题开始时清理上一轮澄清状态和槽位，避免旧上下文污染新问题 */
    public SessionState resetForNewTopic() {
        return new SessionState(SlotBundle.empty(), 0, this.lastIntent, List.of(), null, null);
    }

    /** ASK 更新：clarifyTurn++ + lastAskedFields 追加（Q18） */
    public SessionState onAsk(String askField) {
        List<String> asked = new ArrayList<>(this.lastAskedFields != null ? this.lastAskedFields : List.of());
        if (!asked.contains(askField)) {
            asked.add(askField);
        }
        return new SessionState(this.slots, this.clarifyTurn + 1, this.lastIntent, List.copyOf(asked),
                this.pendingQuery, this.pendingIntent);
    }
}
