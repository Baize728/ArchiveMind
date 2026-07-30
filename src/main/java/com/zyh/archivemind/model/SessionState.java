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
 */
public record SessionState(
        SlotBundle slots,
        int clarifyTurn,
        Intent lastIntent,
        List<String> lastAskedFields
) {
    public static SessionState fresh() {
        return new SessionState(SlotBundle.empty(), 0, null, List.of());
    }

    /** 写入本轮意图（Q21，每轮 route 返回后统一写入） */
    public SessionState withLastIntent(Intent intent) {
        return new SessionState(this.slots, this.clarifyTurn, intent, this.lastAskedFields);
    }

    /** 更新槽位 */
    public SessionState withSlots(SlotBundle slots) {
        return new SessionState(slots, this.clarifyTurn, this.lastIntent, this.lastAskedFields);
    }

    /** READY 后清理：清所有 slots + clarifyTurn + lastAskedFields，保留 lastIntent（Q19/Q22） */
    public SessionState onReady() {
        return new SessionState(SlotBundle.empty(), 0, this.lastIntent, List.of());
    }

    /** 换话题重置：清 clarifyTurn + lastAskedFields，不清 slots（Q18） */
    public SessionState resetClarify() {
        return new SessionState(this.slots, 0, this.lastIntent, List.of());
    }

    /** ASK 更新：clarifyTurn++ + lastAskedFields 追加（Q18） */
    public SessionState onAsk(String askField) {
        List<String> asked = new ArrayList<>(this.lastAskedFields != null ? this.lastAskedFields : List.of());
        if (!asked.contains(askField)) {
            asked.add(askField);
        }
        return new SessionState(this.slots, this.clarifyTurn + 1, this.lastIntent, List.copyOf(asked));
    }
}
