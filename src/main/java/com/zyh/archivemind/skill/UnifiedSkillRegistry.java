package com.zyh.archivemind.skill;

import com.zyh.archivemind.Llm.ToolDefinition;
import com.zyh.archivemind.mcp.client.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一工具注册中心
 * 合并内部 Skill 和外部 MCP Tool，提供统一的工具查找和执行接口
 */
@Component
public class UnifiedSkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedSkillRegistry.class);

    private final SkillRegistry skillRegistry;
    private final McpClientManager mcpClientManager;

    public UnifiedSkillRegistry(SkillRegistry skillRegistry, McpClientManager mcpClientManager) {
        this.skillRegistry = skillRegistry;
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 返回内部 Skill + 外部 MCP Tool 的合并列表
     */
    public List<ToolDefinition> getAllToolDefinitions() {
        List<ToolDefinition> allTools = new ArrayList<>();
        allTools.addAll(skillRegistry.getAllToolDefinitions());
        allTools.addAll(mcpClientManager.discoverAllTools());
        return allTools;
    }

    /**
     * 统一执行工具
     * - 名称含 ":"：拆分为 serverId + mcpToolName，委托 McpClientManager
     * - 名称无 ":"：委托 SkillRegistry 查找内部 Skill 执行
     */
    public SkillResult executeSkill(String toolName, SkillContext context,
                                     Map<String, Object> params) {
        if (toolName.contains(":")) {
            int idx = toolName.indexOf(":");
            String serverId = toolName.substring(0, idx);
            String mcpToolName = toolName.substring(idx + 1);
            logger.info("路由到外部 MCP Tool: serverId={}, tool={}", serverId, mcpToolName);
            return mcpClientManager.callTool(serverId, mcpToolName, params);
        }

        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return SkillResult.failure("未知工具: " + toolName);
        }
        return skill.execute(context, params);
    }
}
