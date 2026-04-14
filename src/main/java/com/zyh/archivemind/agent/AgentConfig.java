package com.zyh.archivemind.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    /** Agent 唯一标识（用于编排时区分不同 Agent） */
    private String agentId;

    /** Agent 名称（用于日志和调试时的可读名称） */
    private String name;

    /** 最大工具调用循环次数 */
    @Builder.Default
    private int maxIterations = 5;

    /** System Prompt（为空时使用 AgentExecutor 的默认 Prompt） */
    private String systemPrompt;
}
