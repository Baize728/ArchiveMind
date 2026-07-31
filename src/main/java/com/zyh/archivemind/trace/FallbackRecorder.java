package com.zyh.archivemind.trace;

/**
 * Fallback 事件记录工具（T1-5 聚合）。
 *
 * 消除各调用方重复的 if(scope != null) scope.recordFallback(...) 样板。
 * 只负责"安全记录"，不做降级判定——降级触发条件在 FallbackPolicyService 内。
 */
public final class FallbackRecorder {

    private FallbackRecorder() {}

    /**
     * 记录 fallback 事件。scope 为 null 时静默跳过（测试场景）。
     */
    public static void record(TraceScope scope, String stage, String layer,
                              String reason, String output) {
        if (scope != null) {
            scope.recordFallback(stage, layer, reason, output);
        }
    }
}
