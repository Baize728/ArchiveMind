package com.zyh.archivemind.intent;

import com.zyh.archivemind.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntentReviseServiceTest {

    private IntentReviseService service;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        // 配置关键词
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", List.of("你好", "谢谢", "再见"),
                "doc_operation", List.of("归档", "上传文档", "删除文档"),
                "knowledge_qa", List.of("查询", "什么是", "怎么")
        ));
        aiProperties.getIntent().setAmbiguousThreshold(0.4);
        service = new IntentReviseService(aiProperties);
    }

    @Test
    @DisplayName("LLM 返回 null 应兜底为 AMBIGUOUS(0.2, KEYWORD)")
    void shouldFallbackToAmbiguousWhenLlmResultIsNull() {
        IntentResult result = service.revise(null, "任何输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals(0.2, result.confidence());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("命中 chitchat 关键词应强制覆盖为 CHITCHAT")
    void shouldForceChitchatWhenKeywordMatched() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好啊");

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("命中 doc_operation 关键词应强制覆盖为 DOC_OPERATION")
    void shouldForceDocOperationWhenKeywordMatched() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "把这份合同归档");

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("doc_operation 关键词优先级高于 chitchat")
    void docOperationShouldTakePrecedenceOverChitchat() {
        // "删除文档" 命中 doc_operation，不应判为 chitchat
        IntentResult llmResult = new IntentResult(Intent.CHITCHAT, 0.8, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "删除文档");

        assertEquals(Intent.DOC_OPERATION, result.intent());
    }

    @Test
    @DisplayName("低置信度应降级为 AMBIGUOUS")
    void shouldDegradeToAmbiguousWhenLowConfidence() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.3, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "查询政策");

        // "查询" 命中 knowledge_qa 关键词，应强制为 KNOWLEDGE_QA 而非降级
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("低置信度且无关键词命中应降级为 AMBIGUOUS")
    void shouldDegradeToAmbiguousWhenLowConfidenceAndNoKeyword() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.3, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "那个东西");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("高置信度且无关键词命中应保留 LLM 结果")
    void shouldKeepLlmResultWhenHighConfidence() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "那个东西");

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("空输入应兜底为 AMBIGUOUS")
    void shouldFallbackToAmbiguousForEmptyInput() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "");

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("null 输入应兜底为 AMBIGUOUS")
    void shouldFallbackToAmbiguousForNullInput() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, null);

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    // ========== 边界条件 ==========

    @Test
    @DisplayName("LLM null 且命中关键词应返回关键词对应意图")
    void shouldReturnKeywordIntentWhenLlmNullAndKeywordMatched() {
        IntentResult result = service.revise(null, "你好啊");

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("KEYWORD", result.source());
        assertEquals(0.2, result.confidence());
    }

    @Test
    @DisplayName("LLM null 且命中 doc_operation 关键词应返回 DOC_OPERATION")
    void shouldReturnDocOperationWhenLlmNullAndKeywordMatched() {
        IntentResult result = service.revise(null, "请帮我把文档归档");

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("LLM null 且无关键词命中应返回 AMBIGUOUS")
    void shouldReturnAmbiguousWhenLlmNullAndNoKeywordMatched() {
        IntentResult result = service.revise(null, "某个完全不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("KEYWORD", result.source());
        assertEquals(0.2, result.confidence());
    }

    @Test
    @DisplayName("confidence 恰好等于阈值 0.4 应保留 LLM 结果")
    void shouldKeepLlmResultWhenConfidenceEqualsThreshold() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.4, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "某个不匹配的输入");

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("confidence 恰好低于阈值 0.399 应降级 AMBIGUOUS")
    void shouldDegradeWhenConfidenceJustBelowThreshold() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.399, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "某个不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("confidence=0 且无关键词应降级 AMBIGUOUS")
    void shouldDegradeWhenZeroConfidence() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.0, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "某个不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("confidence 为负数且无关键词应降级 AMBIGUOUS")
    void shouldDegradeWhenNegativeConfidence() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, -0.5, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "某个不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("CHITCHAT LLM 结果被 doc_operation 关键词覆盖")
    void shouldOverrideChitchatWithDocOperationKeyword() {
        IntentResult llmResult = new IntentResult(Intent.CHITCHAT, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "请帮我上传文档");

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("AMBIGUOUS LLM 结果被 chitchat 关键词覆盖")
    void shouldOverrideAmbiguousWithChitchatKeyword() {
        IntentResult llmResult = new IntentResult(Intent.AMBIGUOUS, 0.3, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "谢谢你");

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("关键词命中时 confidence 应保留 LLM 原始值")
    void shouldPreserveOriginalConfidenceWhenKeywordOverrides() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.7, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好");

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals(0.7, result.confidence());
    }

    @Test
    @DisplayName("关键词命中时 rawReply 应保留 LLM 原始返回")
    void shouldPreserveRawReplyWhenKeywordOverrides() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "原始JSON");

        IntentResult result = service.revise(llmResult, "你好");

        assertEquals("原始JSON", result.rawReply());
    }

    @Test
    @DisplayName("降级时 rawReply 应保留 LLM 原始返回")
    void shouldPreserveRawReplyWhenDegrading() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.1, "LLM", "原始JSON");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("原始JSON", result.rawReply());
    }

    @Test
    @DisplayName("LLM 结果为 CHITCHAT 且高置信度且无关键词应保留 CHITCHAT")
    void shouldKeepChitchatWhenHighConfidenceNoKeyword() {
        IntentResult llmResult = new IntentResult(Intent.CHITCHAT, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("LLM 结果为 DOC_OPERATION 且高置信度且无关键词应保留 DOC_OPERATION")
    void shouldKeepDocOperationWhenHighConfidenceNoKeyword() {
        IntentResult llmResult = new IntentResult(Intent.DOC_OPERATION, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("LLM 结果为 AMBIGUOUS 且高置信度应保留 AMBIGUOUS")
    void shouldKeepAmbiguousWhenHighConfidence() {
        IntentResult llmResult = new IntentResult(Intent.AMBIGUOUS, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("LLM", result.source());
    }

    // ========== 异常情况 ==========

    @Test
    @DisplayName("关键词表为空 Map 时应不匹配关键词，走 confidence 判断")
    void shouldNotMatchKeywordWhenKeywordsMapEmpty() {
        aiProperties.getIntent().setKeywords(Map.of());

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.3, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好");

        // 无关键词表，不命中，低置信度降级
        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("关键词表为 null 时应不匹配关键词，走 confidence 判断")
    void shouldNotMatchKeywordWhenKeywordsNull() {
        aiProperties.getIntent().setKeywords(null);

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.3, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好");

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("关键词列表中含 null 元素时应跳过 null 不报错")
    void shouldSkipNullKeywordInList() {
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", java.util.Arrays.asList("你好", null, "再见")
        ));

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好啊");

        assertEquals(Intent.CHITCHAT, result.intent());
    }

    @Test
    @DisplayName("关键词列表中含空字符串元素应跳过不报错")
    void shouldSkipBlankKeywordInList() {
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", java.util.Arrays.asList("", "你好")
        ));

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好啊");

        assertEquals(Intent.CHITCHAT, result.intent());
    }

    @Test
    @DisplayName("关键词列表为空列表时应不匹配关键词")
    void shouldNotMatchWhenKeywordListEmpty() {
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", List.of()
        ));

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "你好");

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("用户输入含关键词子串应命中（包含匹配）")
    void shouldMatchSubstringKeyword() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");

        // "你好" 是 "你好啊，请问" 的子串
        IntentResult result = service.revise(llmResult, "你好啊，请问");

        assertEquals(Intent.CHITCHAT, result.intent());
    }

    @Test
    @DisplayName("ambiguousThreshold=0 时所有低置信度应保留（阈值=0，只有 confidence<0 才降级）")
    void shouldNotDegradeWhenThresholdIsZero() {
        aiProperties.getIntent().setAmbiguousThreshold(0.0);

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.0, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("ambiguousThreshold=1.0 时所有 confidence<1.0 都应降级")
    void shouldDegradeAllWhenThresholdIsOne() {
        aiProperties.getIntent().setAmbiguousThreshold(1.0);

        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.99, "LLM", "raw");

        IntentResult result = service.revise(llmResult, "不匹配的输入");

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }
}
