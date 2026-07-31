package com.zyh.archivemind.fallback;

import com.zyh.archivemind.clarify.ClarifyRuleService;
import com.zyh.archivemind.clarify.SlotBundle;
import com.zyh.archivemind.client.ClarifyLlmClient;
import com.zyh.archivemind.common.DomainAliasMatcher;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.intent.Intent;
import com.zyh.archivemind.intent.IntentResult;
import com.zyh.archivemind.model.SessionState;
import com.zyh.archivemind.trace.TraceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FallbackPolicyServiceTest {

    @Mock private ClarifyRuleService clarifyRuleService;
    @Mock private ClarifyLlmClient clarifyLlmClient;

    private FallbackPolicyService service;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", List.of("你好", "谢谢"),
                "doc_operation", List.of("归档", "上传文档"),
                "knowledge_qa", List.of("查询", "什么是")
        ));
        aiProperties.getIntent().setAmbiguousThreshold(0.4);

        DomainAliasMatcher domainAliasMatcher = new DomainAliasMatcher();
        domainAliasMatcher.init();

        service = new FallbackPolicyService(aiProperties, domainAliasMatcher,
                clarifyRuleService, clarifyLlmClient);
    }

    // ── intent 环节 ──

    @Test
    @DisplayName("intent: LLM null + 关键词命中 → KEYWORD 兜底")
    void intent_llmNull_keywordMatched() {
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").llmIntentResult(null).userInput("你好").sessionState(null).build();
        IntentResult result = (IntentResult) service.execute("intent", ctx);
        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("intent: LLM null 无关键词 → AMBIGUOUS 兜底")
    void intent_llmNull_noKeyword() {
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").llmIntentResult(null).userInput("某个不匹配的输入").sessionState(null).build();
        IntentResult result = (IntentResult) service.execute("intent", ctx);
        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("intent: 强制关键词覆盖 LLM 结果")
    void intent_keywordOverride() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").llmIntentResult(llmResult).userInput("你好").sessionState(null).build();
        IntentResult result = (IntentResult) service.execute("intent", ctx);
        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("intent: 低置信度降级 AMBIGUOUS")
    void intent_lowConfidenceDegrade() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.3, "LLM", "raw");
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").llmIntentResult(llmResult).userInput("某个不匹配的输入").sessionState(null).build();
        IntentResult result = (IntentResult) service.execute("intent", ctx);
        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("intent: 高置信度无关键词 → 保留 LLM 结果")
    void intent_highConfidenceKeepLlm() {
        IntentResult llmResult = new IntentResult(Intent.KNOWLEDGE_QA, 0.9, "LLM", "raw");
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").llmIntentResult(llmResult).userInput("某个不匹配的输入").sessionState(null).build();
        IntentResult result = (IntentResult) service.execute("intent", ctx);
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("intent: TraceScope 非 null 时记录 FALLBACK")
    void intent_recordsFallbackTrace() {
        TraceScope scope = spy(TraceScope.noop());
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent").traceScope(scope)
                .llmIntentResult(null).userInput("你好").sessionState(null).build();
        service.execute("intent", ctx);
        verify(scope).recordFallback(eq("intent"), eq("keyword"), eq("llm_null"), anyString());
    }

    // ── slot 环节 ──

    @Test
    @DisplayName("slot: clarify 禁用 → 空 bundle")
    void slot_clarifyDisabled() {
        aiProperties.getClarify().setEnabled(false);
        FallbackContext ctx = FallbackContext.builder()
                .stage("slot").userInput("测试").build();
        SlotBundle result = (SlotBundle) service.execute("slot", ctx);
        assertNotNull(result);
        assertNull(result.domain());
    }

    @Test
    @DisplayName("slot: LLM 返回空 → 空 bundle")
    void slot_llmNull() {
        aiProperties.getClarify().setEnabled(true);
        when(clarifyLlmClient.chatSync(anyList(), anyInt(), anyDouble())).thenReturn(null);
        FallbackContext ctx = FallbackContext.builder()
                .stage("slot").userInput("测试").build();
        SlotBundle result = (SlotBundle) service.execute("slot", ctx);
        assertNotNull(result);
        assertNull(result.domain());
    }

    @Test
    @DisplayName("slot: LLM 返回非 JSON → 空 bundle")
    void slot_parseFail() {
        aiProperties.getClarify().setEnabled(true);
        when(clarifyLlmClient.chatSync(anyList(), anyInt(), anyDouble())).thenReturn("这不是JSON");
        FallbackContext ctx = FallbackContext.builder()
                .stage("slot").userInput("测试").build();
        SlotBundle result = (SlotBundle) service.execute("slot", ctx);
        assertNotNull(result);
        assertNull(result.domain());
    }

    @Test
    @DisplayName("slot: LLM 异常 → 空 bundle")
    void slot_llmException() {
        aiProperties.getClarify().setEnabled(true);
        when(clarifyLlmClient.chatSync(anyList(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("超时"));
        FallbackContext ctx = FallbackContext.builder()
                .stage("slot").userInput("测试").build();
        SlotBundle result = (SlotBundle) service.execute("slot", ctx);
        assertNotNull(result);
        assertNull(result.domain());
    }

    @Test
    @DisplayName("slot: LLM 返回有效 JSON → 解析槽位")
    void slot_llmValidJson() {
        aiProperties.getClarify().setEnabled(true);
        when(clarifyLlmClient.chatSync(anyList(), anyInt(), anyDouble()))
                .thenReturn("{\"domain\":\"finance\",\"docScope\":null,\"timeRange\":null,\"entity\":\"差旅报销\"}");
        FallbackContext ctx = FallbackContext.builder()
                .stage("slot").userInput("测试").build();
        SlotBundle result = (SlotBundle) service.execute("slot", ctx);
        assertEquals("finance", result.domain());
        assertEquals("差旅报销", result.entity());
    }

    // ── clarify 环节 ──

    @Test
    @DisplayName("clarify: 返回模板追问")
    void clarify_returnsTemplate() {
        when(clarifyRuleService.fallbackQuestion(anyList(), anyBoolean()))
                .thenReturn("请问是哪个业务域？");
        FallbackContext ctx = FallbackContext.builder()
                .stage("clarify")
                .missingSlots(List.of("domain"))
                .clarifyTurn(0)
                .build();
        var result = (com.zyh.archivemind.clarify.ClarifyResult) service.execute("clarify", ctx);
        assertNotNull(result);
        assertEquals("请问是哪个业务域？", result.questionToAsk());
    }

    // ── chitchat 环节 ──

    @Test
    @DisplayName("chitchat: 返回固定文案")
    void chitchat_returnsTemplate() {
        FallbackContext ctx = FallbackContext.builder()
                .stage("chitchat").build();
        String result = (String) service.execute("chitchat", ctx);
        assertEquals(aiProperties.getFallback().getChitchatTemplate(), result);
    }

    // ── answer 环节 ──

    @Test
    @DisplayName("answer: 全空断流 → answerTemplate")
    void answer_emptyStream() {
        FallbackContext ctx = FallbackContext.builder()
                .stage("answer")
                .error(new RuntimeException("断流"))
                .existingResponse("")
                .build();
        String result = (String) service.execute("answer", ctx);
        assertEquals(aiProperties.getFallback().getAnswerTemplate(), result);
    }

    @Test
    @DisplayName("answer: 部分断流 → answerPartialTemplate")
    void answer_partialStream() {
        FallbackContext ctx = FallbackContext.builder()
                .stage("answer")
                .error(new RuntimeException("断流"))
                .existingResponse("已有半截回答")
                .build();
        String result = (String) service.execute("answer", ctx);
        assertEquals(aiProperties.getFallback().getAnswerPartialTemplate(), result);
    }

    @Test
    @DisplayName("answer: 空回复无异常 → answerTemplate + reason=llm_empty_output")
    void answer_emptyOutput() {
        TraceScope scope = spy(TraceScope.noop());
        FallbackContext ctx = FallbackContext.builder()
                .stage("answer")
                .traceScope(scope)
                .error(null)
                .existingResponse("")
                .build();
        String result = (String) service.execute("answer", ctx);
        assertEquals(aiProperties.getFallback().getAnswerTemplate(), result);
        verify(scope).recordFallback(eq("answer"), eq("template"), eq("llm_empty_output"), anyString());
    }

    @Test
    @DisplayName("answer: 未知 stage 抛异常")
    void unknownStage_throws() {
        FallbackContext ctx = FallbackContext.builder().stage("unknown").build();
        assertThrows(IllegalArgumentException.class, () -> service.execute("unknown", ctx));
    }
}
