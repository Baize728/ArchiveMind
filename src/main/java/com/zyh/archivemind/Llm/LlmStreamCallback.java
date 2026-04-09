package com.zyh.archivemind.Llm;

/**
 * LLM 流式响应回调接口
 * 用于处理 LLM 返回的各种事件类型
 */
public interface LlmStreamCallback {

    /**
     * 收到文本块时回调
     * @param chunk 文本片段
     */
    void onTextChunk(String chunk);

    /**
     * 收到工具调用指令时回调
     * @param toolCall 工具调用信息（包含函数名和参数）
     */
    void onToolCall(ToolCall toolCall);

    /**
     * 流式响应完成时回调
     */
    void onComplete();

    /**
     * 发生错误时回调
     * @param error 异常信息
     */
    void onError(Throwable error);
}
