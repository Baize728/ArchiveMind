package com.zyh.archivemind.Llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LlmRequest {

    /** 模型名称（可选，为空时使用 Provider 默认模型） */
    private String model;

    /** 消息列表（system + history + user） */
    private List<LlmMessage> messages;

    /** 工具定义列表（可选，为空时不启用 Function Calling） */
    private List<ToolDefinition> tools;

    /** 生成参数（temperature、maxTokens 等） */
    private GenerationParams params;
}
