package com.zyh.archivemind.Llm;

import com.zyh.archivemind.Tool.Tool;
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

    /** 工具列表（可选，为空时不启用 Function Calling，Tool 接口直接提供 LLM 所需定义） */
    private List<Tool> tools;

    /** 生成参数（temperature、maxTokens 等） */
    private GenerationParams params;
}
