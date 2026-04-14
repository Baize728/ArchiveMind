package com.zyh.archivemind.agent;

import com.zyh.archivemind.Llm.ToolCall;
import com.zyh.archivemind.skill.SkillResult;

/**
 * Agent 事件回调接口
 * 用于向外部（如 WebSocket）通知 Agent 执行过程中的各种事件
 */
public interface AgentCallback {

    /** 收到思考过程内容（如 DeepSeek R1 的 reasoning_content） */
    void onThinkingChunk(String chunk);

    /** 收到文本块 */
    void onTextChunk(String chunk);

    /** 工具调用开始 */
    void onToolCallStart(ToolCall toolCall);

    /** 工具调用完成 */
    void onToolCallEnd(ToolCall toolCall, SkillResult result);

    /** Agent 执行完成 */
    void onComplete();

    /** Agent 执行出错 */
    void onError(Throwable error);
}
