package com.zyh.archivemind.skill;

import com.zyh.archivemind.Llm.ToolDefinition;

import java.util.Map;

/**
 * Skill（技能/工具）统一接口
 * 所有可被 Agent 调用的工具都必须实现此接口
 * 实现类使用 @Component 注解，由 Spring 自动扫描注册到 SkillRegistry
 */
public interface Skill {

    /** 获取 Skill 唯一名称（对应 ToolDefinition.name） */
    String getName();

    /** 获取 Skill 描述（LLM 根据此描述决定是否调用） */
    String getDescription();

    /** 获取参数 JSON Schema */
    Map<String, Object> getParameterSchema();

    /**
     * 执行 Skill
     * @param context 执行上下文（包含用户信息、会话信息等）
     * @param params  调用参数（由 LLM 生成，经 ToolCallParser 解析）
     * @return 执行结果
     */
    SkillResult execute(SkillContext context, Map<String, Object> params);

    /** 执行超时秒数，默认 30 秒 */
    default int getTimeoutSeconds() {
        return 30;
    }

    /** 转换为 ToolDefinition（供 LLM 使用） */
    default ToolDefinition toToolDefinition() {
        return ToolDefinition.builder()
                .name(getName())
                .description(getDescription())
                .parameters(getParameterSchema())
                .build();
    }
}
