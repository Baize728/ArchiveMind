package com.zyh.archivemind.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * 自动扫描 Spring 容器中所有 Tool Bean 并注册
 */
@Component
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 构造函数：自动注入所有 Tool Bean
     * Spring 会收集容器中所有实现了 Tool 接口的 Bean
     */
    public ToolRegistry(List<Tool> toolList) {
        for (Tool tool : toolList) {
            tools.put(tool.getName(), tool);
            logger.info("注册 Tool: {} - {}", tool.getName(), tool.getDescription());
        }
        logger.info("ToolRegistry 初始化完成，共注册 {} 个 Tool", tools.size());
    }

    /**
     * 根据名称获取 Tool
     * @return Tool 实例，不存在时返回 null
     */
    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            logger.warn("未找到 Tool: {}，可用: {}", name, tools.keySet());
        }
        return tool;
    }

    /**
     * 获取所有 Tool（供 LLM 使用，直接作为工具定义）
     */
    public List<Tool> getAll() {
        return new ArrayList<>(tools.values());
    }
}
