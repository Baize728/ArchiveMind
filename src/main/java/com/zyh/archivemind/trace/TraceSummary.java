package com.zyh.archivemind.trace;

/**
 * Trace 列表摘要
 * 按 traceId 去重后的会话摘要行，供前端 Trace 列表页展示。
 */
public class TraceSummary {
    public String traceId;
    public String conversationId;
    public String userId;
    public String sessionId;
    public String createdAt;
    public int eventCount;
    public long totalInputTokens;
    public long totalOutputTokens;
    public long totalLatencyMs;
    public int llmCallCount;
    public boolean hasError;
    public String errorMessage;
    public String firstUserInput;
}
