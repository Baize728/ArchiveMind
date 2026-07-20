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

    /**
     * 动态注册一个 Tool（供 MCP 等外部工具源使用）
     * 使用 putIfAbsent 防止同名覆盖
     * @return true 如果注册成功，false 如果名称冲突
     */
    public boolean register(Tool tool) {
        String name = tool.getName();
        Tool existing = tools.putIfAbsent(name, tool);
        if (existing != null) {
            logger.warn("Tool '{}' 已存在，注册被拒绝", name);
            return false;
        }
        logger.info("动态注册 Tool: {} - {}", name, tool.getDescription());
        return true;
    }

    /**
     * 动态注销一个 Tool
     * @return 被移除的 Tool，如果不存在返回 null
     */
    public Tool unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            logger.info("动态注销 Tool: {}", name);
        }
        return removed;
    }
}
