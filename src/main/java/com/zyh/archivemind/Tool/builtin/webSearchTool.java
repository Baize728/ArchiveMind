package com.zyh.archivemind.Tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.web.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class webSearchTool implements Tool {

    private SearchProvider searchProvider;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。";
    }

    @Override
    public JsonNode getParameterSchema() {
        return Tool.buildSchema(
                new Tool.Param("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                new Tool.Param("top_k", "integer", "返回结果数量（默认5）", false)
        );
    }

    private synchronized SearchProvider getSearchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    @Override
    public Tool.ToolResult execute(ToolContext context, Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        if (query.trim().isEmpty()) {
            return Tool.ToolResult.failure("搜索关键词不能为空");
        }

        int topK = 5;
        Object topKObj = params.get("top_k");
        if (topKObj instanceof Number) {
            topK = ((Number) topKObj).intValue();
        }

        SearchProvider provider = getSearchProvider();
        if (!provider.isReady()) {
            return Tool.ToolResult.failure("⚠️ " + provider.unavailableHint());
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return Tool.ToolResult.success(formatSearchResults(provider.name(), query, results));
        } catch (Exception e) {
            return Tool.ToolResult.failure("搜索失败 (" + provider.name() + "): " + e.getMessage());
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
