package com.zyh.archivemind.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 执行上下文
 * 包含当前用户、会话等信息，供 Skill 执行时使用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillContext {

    /** 用户ID（用户名） */
    private String userId;

    /** WebSocket 会话ID */
    private String sessionId;

    /** 对话ID */
    private String conversationId;
}
