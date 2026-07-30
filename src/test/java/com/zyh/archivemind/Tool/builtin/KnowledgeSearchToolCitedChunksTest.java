package com.zyh.archivemind.Tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * V6 验收：citedChunks 透出
 *
 * 测试 KnowledgeSearchTool 输出的 JSON 是否包含 chunkId 和 fileMd5。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolCitedChunksTest {

    @Mock private HybridSearchService searchService;

    private KnowledgeSearchTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(searchService);
    }

    @Test
    @DisplayName("搜索结果输出 JSON 含 chunkId 和 fileMd5")
    void testOutputContainsChunkIdAndFileMd5() throws Exception {
        SearchResult result = new SearchResult("abc123md5", 42, "差旅报销标准内容", 0.95, "报销制度.pdf");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(result));

        Tool.ToolContext ctx = new Tool.ToolContext("user-1", null, null);
        Map<String, Object> params = Map.of("query", "差旅报销标准");

        Tool.ToolResult toolResult = tool.execute(ctx, params);
        assertTrue(toolResult.success());

        JsonNode json = objectMapper.readTree(toolResult.content());
        assertTrue(json.isArray());
        assertEquals(1, json.size());

        JsonNode item = json.get(0);
        assertEquals("1", item.get("index").asText());
        assertEquals("报销制度.pdf", item.get("file").asText());
        assertEquals(42, item.get("chunkId").asInt(), "chunkId 应为 42");
        assertEquals("abc123md5", item.get("fileMd5").asText(), "fileMd5 应为 abc123md5");
        assertEquals("差旅报销标准内容", item.get("content").asText());
    }

    @Test
    @DisplayName("多个搜索结果都含 chunkId/fileMd5")
    void testMultipleResultsAllContainChunkId() throws Exception {
        SearchResult r1 = new SearchResult("md5_1", 1, "内容1", 0.9, "file1.pdf");
        SearchResult r2 = new SearchResult("md5_2", 2, "内容2", 0.8, "file2.pdf");
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(r1, r2));

        Tool.ToolContext ctx = new Tool.ToolContext("user-1", null, null);
        Map<String, Object> params = Map.of("query", "测试");

        Tool.ToolResult toolResult = tool.execute(ctx, params);
        assertTrue(toolResult.success());

        JsonNode json = objectMapper.readTree(toolResult.content());
        assertEquals(2, json.size());

        assertEquals(1, json.get(0).get("chunkId").asInt());
        assertEquals("md5_1", json.get(0).get("fileMd5").asText());

        assertEquals(2, json.get(1).get("chunkId").asInt());
        assertEquals("md5_2", json.get(1).get("fileMd5").asText());
    }

    @Test
    @DisplayName("空搜索结果返回提示文本")
    void testEmptyResults() {
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        Tool.ToolContext ctx = new Tool.ToolContext("user-1", null, null);
        Map<String, Object> params = Map.of("query", "不存在的内容");

        Tool.ToolResult toolResult = tool.execute(ctx, params);
        assertTrue(toolResult.success());
        assertTrue(toolResult.content().contains("未找到"));
    }
}
