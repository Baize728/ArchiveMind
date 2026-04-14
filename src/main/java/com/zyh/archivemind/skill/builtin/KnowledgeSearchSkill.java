package com.zyh.archivemind.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.skill.Skill;
import com.zyh.archivemind.skill.SkillContext;
import com.zyh.archivemind.skill.SkillResult;
import com.zyh.archivemind.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库搜索 Skill
 * 封装 HybridSearchService.searchWithPermission()
 * 从 ChatHandler.executeToolCall() 中提取而来
 */
@Component
public class KnowledgeSearchSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSearchSkill.class);
    private static final int DEFAULT_TOP_K = 5;

    private final HybridSearchService searchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeSearchSkill(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "搜索用户的私有知识库，返回相关文档片段。当需要查阅资料、回答具体问题时调用。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of(
                "query", Map.of(
                        "type", "string",
                        "description", "搜索关键词或问题"
                )
        ));
        parameters.put("required", List.of("query"));
        return parameters;
    }

    @Override
    public SkillResult execute(SkillContext context, Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        if (query.trim().isEmpty()) {
            return SkillResult.failure("搜索查询不能为空");
        }

        try {
            logger.info("执行知识库搜索: query={}, userId={}", query, context.getUserId());
            List<SearchResult> results = searchService.searchWithPermission(
                    query, context.getUserId(), DEFAULT_TOP_K);

            if (results.isEmpty()) {
                return SkillResult.success("未找到与 \"" + query + "\" 相关的文档");
            }

            // 格式化搜索结果（使用 ObjectMapper 安全序列化，与原 ChatHandler 逻辑一致）
            List<Map<String, String>> formatted = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                String snippet = r.getTextContent();
                if (snippet.length() > 800) {
                    snippet = snippet.substring(0, 800) + "…";
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("index", String.valueOf(i + 1));
                item.put("file", r.getFileName() != null ? r.getFileName() : "unknown");
                item.put("content", snippet);
                formatted.add(item);
            }

            return SkillResult.success(objectMapper.writeValueAsString(formatted));
        } catch (Exception e) {
            logger.error("知识库搜索失败: {}", e.getMessage(), e);
            return SkillResult.failure("搜索失败: " + e.getMessage());
        }
    }
}
