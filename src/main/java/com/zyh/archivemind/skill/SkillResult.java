package com.zyh.archivemind.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillResult {

    /** 是否执行成功 */
    private boolean success;

    /** 结果内容（文本格式，供 LLM 理解） */
    private String content;

    public static SkillResult success(String content) {
        return SkillResult.builder().success(true).content(content).build();
    }

    public static SkillResult failure(String errorMessage) {
        return SkillResult.builder().success(false).content(errorMessage).build();
    }
}
