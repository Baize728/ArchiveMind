package com.zyh.archivemind.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 LLM 输出文本中容错提取 JSON 对象。
 * 借鉴 Diet-Agent 的 LlmJsonService.parseObject，但失败时返回 null 而非抛异常——
 * 意图识别失败应静默兜底，不应抛异常打断 ChatHandler。
 */
public final class LlmJsonExtractor {

    private static final Logger logger = LoggerFactory.getLogger(LlmJsonExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJsonExtractor() {
    }

    /**
     * 从 LLM 输出文本中提取 JSON 对象。
     * 容错：去除 markdown 代码块标记，截取首个 '{' 到末个 '}'。
     *
     * @param content LLM 返回的原始文本
     * @return 解析成功的 JsonNode；输入空或解析失败返回 null
     */
    public static JsonNode parseObject(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = content.strip()
                .replace("```json", "")
                .replace("```", "")
                .strip();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end < start) {
            logger.warn("LLM 输出未包含合法 JSON 对象: {}", content);
            return null;
        }
        try {
            return MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            logger.warn("LLM JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
