package com.zyh.archivemind.skill;

import com.zyh.archivemind.Llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 注册中心
 * 自动扫描 Spring 容器中所有 Skill Bean 并注册
 */
@Component
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /**
     * 构造函数：自动注入所有 Skill Bean
     * Spring 会收集容器中所有实现了 Skill 接口的 Bean
     */
    public SkillRegistry(List<Skill> skillList) {
        for (Skill skill : skillList) {
            skills.put(skill.getName(), skill);
            logger.info("注册 Skill: {} - {}", skill.getName(), skill.getDescription());
        }
        logger.info("SkillRegistry 初始化完成，共注册 {} 个 Skill", skills.size());
    }

    /**
     * 根据名称获取 Skill
     * @return Skill 实例，不存在时返回 null
     */
    public Skill getSkill(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            logger.warn("未找到 Skill: {}，可用: {}", name, skills.keySet());
        }
        return skill;
    }

    /**
     * 获取所有 Skill 的 ToolDefinition（供 LLM 使用）
     * 如果没有注册任何 Skill，返回空列表
     */
    public List<ToolDefinition> getAllToolDefinitions() {
        return skills.values().stream()
                .map(Skill::toToolDefinition)
                .collect(Collectors.toList());
    }
}
