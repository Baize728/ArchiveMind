package com.zyh.archivemind.Llm;

/**
 * LLM 提供商统一接口
 * 所有 LLM 实现（DeepSeek、OpenAI、Ollama、通义千问等）都必须实现此接口
 */
public interface LlmProvider {

    /**
     * 获取提供商唯一标识
     * @return 提供商ID，如 "deepseek"、"openai"、"ollama"
     */
    String getProviderId();

    /**
     * 是否支持 Function Calling / Tool Use
     * @return true 表示支持工具调用
     */
    boolean supportsToolCalling();

    /**
     * 流式聊天调用
     * @param request LLM 请求参数（包含消息列表、工具定义等）
     * @param callback 流式回调接口（处理文本块、工具调用等事件）
     */
    void streamChat(LlmRequest request, LlmStreamCallback callback);
}
