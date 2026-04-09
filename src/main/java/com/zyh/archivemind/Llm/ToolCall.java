package com.zyh.archivemind.Llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用信息
 * LLM 返回的工具调用指令
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** 调用 ID（用于关联工具结果） */
    private String id;

    /** 函数名称 */
    private String functionName;

    /** 函数参数（JSON 字符串） */
    private String arguments;
}
