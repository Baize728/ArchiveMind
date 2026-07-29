package com.zyh.archivemind.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmJsonExtractorTest {

    @Test
    @DisplayName("标准 JSON 对象应正确解析")
    void shouldParseStandardJson() {
        JsonNode node = LlmJsonExtractor.parseObject("{\"intent\":\"CHITCHAT\",\"confidence\":0.8}");
        assertNotNull(node);
        assertEquals("CHITCHAT", node.path("intent").asText());
        assertEquals(0.8, node.path("confidence").asDouble());
    }

    @Test
    @DisplayName("markdown 代码块包裹的 JSON 应正确解析")
    void shouldParseMarkdownJson() {
        JsonNode node = LlmJsonExtractor.parseObject("```json\n{\"intent\":\"CHITCHAT\"}\n```");
        assertNotNull(node);
        assertEquals("CHITCHAT", node.path("intent").asText());
    }

    @Test
    @DisplayName("纯 markdown 代码块（无 json 标记）应正确解析")
    void shouldParsePlainCodeBlock() {
        JsonNode node = LlmJsonExtractor.parseObject("```\n{\"intent\":\"CHITCHAT\"}\n```");
        assertNotNull(node);
        assertEquals("CHITCHAT", node.path("intent").asText());
    }

    @Test
    @DisplayName("JSON 前后有多余文本应截取中间 JSON")
    void shouldExtractJsonFromSurroundingText() {
        JsonNode node = LlmJsonExtractor.parseObject("好的，这是结果：{\"intent\":\"KNOWLEDGE_QA\"} 以上。");
        assertNotNull(node);
        assertEquals("KNOWLEDGE_QA", node.path("intent").asText());
    }

    @Test
    @DisplayName("null 输入应返回 null")
    void shouldReturnNullForNullInput() {
        assertNull(LlmJsonExtractor.parseObject(null));
    }

    @Test
    @DisplayName("空字符串应返回 null")
    void shouldReturnNullForEmptyString() {
        assertNull(LlmJsonExtractor.parseObject(""));
    }

    @Test
    @DisplayName("纯空白字符串应返回 null")
    void shouldReturnNullForBlankString() {
        assertNull(LlmJsonExtractor.parseObject("   \n\t  "));
    }

    @Test
    @DisplayName("不含大括号的文本应返回 null")
    void shouldReturnNullForNoBraces() {
        assertNull(LlmJsonExtractor.parseObject("这不是JSON"));
    }

    @Test
    @DisplayName("只有左大括号应返回 null")
    void shouldReturnNullForOnlyOpenBrace() {
        assertNull(LlmJsonExtractor.parseObject("{不完整"));
    }

    @Test
    @DisplayName("只有右大括号应返回 null")
    void shouldReturnNullForOnlyCloseBrace() {
        assertNull(LlmJsonExtractor.parseObject("不完整}"));
    }

    @Test
    @DisplayName("大括号顺序颠倒应返回 null")
    void shouldReturnNullForReversedBraces() {
        assertNull(LlmJsonExtractor.parseObject("}{"));
    }

    @Test
    @DisplayName("非法 JSON 内容应返回 null")
    void shouldReturnNullForInvalidJsonContent() {
        assertNull(LlmJsonExtractor.parseObject("{invalid json content}"));
    }

    @Test
    @DisplayName("嵌套 JSON 对象应正确解析")
    void shouldParseNestedJson() {
        JsonNode node = LlmJsonExtractor.parseObject(
                "{\"intent\":\"KNOWLEDGE_QA\",\"slots\":{\"domain\":\"法务\"}}");
        assertNotNull(node);
        assertEquals("法务", node.path("slots").path("domain").asText());
    }

    @Test
    @DisplayName("空 JSON 对象应正确解析")
    void shouldParseEmptyJsonObject() {
        JsonNode node = LlmJsonExtractor.parseObject("{}");
        assertNotNull(node);
        assertTrue(node.isEmpty());
    }

    @Test
    @DisplayName("含转义字符的 JSON 应正确解析")
    void shouldParseJsonWithEscapedChars() {
        JsonNode node = LlmJsonExtractor.parseObject(
                "{\"content\":\"hello\\nworld\"}");
        assertNotNull(node);
        assertEquals("hello\nworld", node.path("content").asText());
    }
}
