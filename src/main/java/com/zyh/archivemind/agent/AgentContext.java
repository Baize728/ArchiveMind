package com.zyh.archivemind.agent;

import com.zyh.archivemind.Llm.LlmMessage;
import com.zyh.archivemind.skill.SkillContext;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行上下文
 * 维护整个 Agent 执行过程中的状态
 */
@Data
@Builder
public class AgentContext {

    /** Skill 执行上下文 */
    private SkillContext skillContext;

    /** 消息列表（随 Agent 循环不断追加） */
    @Builder.Default
    private List<LlmMessage> messages = new ArrayList<>();

    /** 当前循环次数 */
    @Builder.Default
    private int currentIteration = 0;
}
