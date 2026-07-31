package com.zyh.archivemind.Tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库搜索 Tool
 * 封装 HybridSearchService.searchWithPermission()
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSearchTool.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HybridSearchService searchService;

    public KnowledgeSearchTool(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "在知识库中搜索与用户问题相关的文档片段。当用户问题的答案可能依赖已上传资料、企业/项目/产品/系统内部信息、专有名词、事实依据、定义、功能、使用方式、实现细节、背景、流程或引用来源时应调用；即使用户没有明确说“查询知识库”，只要问题不像纯通用常识也应先检索。普通问候、闲聊、纯创作、翻译、通用代码/常识问题，或用户明确要求不要查知识库时不要调用。";
    }

    @Override
    public JsonNode getParameterSchema() {
        return Tool.buildSchema(
                new Tool.Param("query", "string", "用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。", true),
                new Tool.Param("topK", "integer", "返回的片段数量，默认 5。", false)
        );
    }

    @Override
    public Tool.ToolResult execute(Tool.ToolContext context, Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        int topK = (int) params.getOrDefault("topK", DEFAULT_TOP_K);
        if (query.trim().isEmpty()) {
            return Tool.ToolResult.failure("搜索查询不能为空");
        }

        try {
            logger.info("执行知识库搜索: query={}, userId={}", query, context.userId());
            List<SearchResult> results = searchService.searchWithPermission(
                    query, context.userId(), topK);

            if (results.isEmpty()) {
                return Tool.ToolResult.success("未找到与 \"" + query + "\" 相关的文档");
            }

            // 格式化搜索结果：返回完整内容，不再截断（让 LLM 获取完整上下文）
            List<Map<String, String>> formatted = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                Map<String, String> item = new LinkedHashMap<>();
                item.put("index", String.valueOf(i + 1));
                item.put("file", r.getFileName() != null ? r.getFileName() : "unknown");
                item.put("content", r.getTextContent());
                formatted.add(item);
            }

            return Tool.ToolResult.success(objectMapper.writeValueAsString(formatted));
        } catch (Exception e) {
            logger.error("知识库搜索失败: {}", e.getMessage(), e);
            return Tool.ToolResult.failure("搜索失败: " + e.getMessage());
        }
    }
}
