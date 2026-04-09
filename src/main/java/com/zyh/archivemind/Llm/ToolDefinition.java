package com.zyh.archivemind.Llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * 工具定义
 * 描述一个可被 LLM 调用的工具（Function）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具名称（唯一标识） */
    private String name;

    /** 工具描述（LLM 根据此描述决定是否调用） */
    private String description;

    /** 参数 JSON Schema（定义工具接受的参数结构） */
    private Map<String, Object> parameters;
}
