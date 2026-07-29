package com.zyh.archivemind.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentResultTest {

    @Test
    @DisplayName("keywordFallback 应创建 confidence=0.2, source=KEYWORD 的结果")
    void keywordFallbackShouldCreateCorrectResult() {
        IntentResult result = IntentResult.keywordFallback(Intent.AMBIGUOUS);

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals(0.2, result.confidence());
        assertEquals("KEYWORD", result.source());
        assertEquals("", result.rawReply());
    }

    @Test
    @DisplayName("keywordFallback 对每种意图都应正常创建")
    void keywordFallbackShouldWorkForAllIntents() {
        for (Intent intent : Intent.values()) {
            IntentResult result = IntentResult.keywordFallback(intent);
            assertEquals(intent, result.intent());
            assertEquals(0.2, result.confidence());
            assertEquals("KEYWORD", result.source());
        }
    }

    @Test
    @DisplayName("正常构造应保留所有字段")
    void normalConstructorShouldPreserveAllFields() {
        IntentResult result = new IntentResult(Intent.KNOWLEDGE_QA, 0.95, "LLM", "raw json");

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals(0.95, result.confidence());
        assertEquals("LLM", result.source());
        assertEquals("raw json", result.rawReply());
    }

    @Test
    @DisplayName("confidence=0 应正常创建")
    void shouldCreateWithZeroConfidence() {
        IntentResult result = new IntentResult(Intent.AMBIGUOUS, 0.0, "RULE", "");
        assertEquals(0.0, result.confidence());
    }

    @Test
    @DisplayName("confidence=1.0 应正常创建")
    void shouldCreateWithMaxConfidence() {
        IntentResult result = new IntentResult(Intent.KNOWLEDGE_QA, 1.0, "LLM", "");
        assertEquals(1.0, result.confidence());
    }
}
