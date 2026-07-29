package com.zyh.archivemind.intent;

import com.zyh.archivemind.client.IntentLlmClient;
import com.zyh.archivemind.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentRouterTest {

    @Mock private IntentLlmClient intentLlmClient;

    private IntentRouter router;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getIntent().setEnabled(true);
        aiProperties.getIntent().setKeywords(Map.of(
                "chitchat", List.of("你好", "谢谢", "再见"),
                "doc_operation", List.of("归档", "上传文档", "删除文档"),
                "knowledge_qa", List.of("查询", "什么是", "怎么")
        ));
        aiProperties.getIntent().setAmbiguousThreshold(0.4);

        IntentAgentService agentService = new IntentAgentService(intentLlmClient, aiProperties);
        IntentReviseService reviseService = new IntentReviseService(aiProperties);
        router = new IntentRouter(agentService, reviseService);
    }

    @Test
    @DisplayName("场景1: 知识问答正常路径 → KNOWLEDGE_QA")
    void scenario1_KnowledgeQaNormalPath() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        // 输入不含关键词，LLM 结果应保留
        IntentResult result = router.route("差旅费报销的审批节点有哪些", List.of());

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        // 输入不含任何关键词，LLM 结果保留
        assertEquals("LLM", result.source());
        assertTrue(result.confidence() >= 0.4);
    }

    @Test
    @DisplayName("场景2: 闲聊关键词强制覆盖 → CHITCHAT")
    void scenario2_ChitchatKeywordOverride() {
        // LLM 判成 KNOWLEDGE_QA，但关键词"你好"应强制覆盖为 CHITCHAT
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.8}");

        IntentResult result = router.route("你好", List.of());

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("场景3: LLM 超时 → 关键词兜底命中 DOC_OPERATION")
    void scenario3_LlmTimeoutKeywordFallback() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = router.route("把这份合同归档", List.of());

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("KEYWORD", result.source());
        assertEquals(0.2, result.confidence());
    }

    @Test
    @DisplayName("场景4: LLM 超时且无关键词命中 → 兜底 AMBIGUOUS")
    void scenario4_LlmTimeoutNoKeywordFallback() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = router.route("那个东西", List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("KEYWORD", result.source());
        assertEquals(0.2, result.confidence());
    }

    @Test
    @DisplayName("场景5: 低置信度降级 → AMBIGUOUS")
    void scenario5_LowConfidenceDegrade() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.3}");

        IntentResult result = router.route("那个东西", List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("场景6: LLM 返回非法 JSON → 兜底 AMBIGUOUS")
    void scenario6_InvalidJsonFallback() {
        when(intentLlmClient.chatSync(anyList())).thenReturn("这不是JSON");

        IntentResult result = router.route("随便输入", List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("场景7: 意图识别未启用 → 直接走关键词/兜底")
    void scenario7_DisabledIntentRecognition() {
        aiProperties.getIntent().setEnabled(false);

        IntentResult result = router.route("你好", List.of());

        // 未启用 LLM，但关键词仍然生效（IntentReviseService 不依赖 enabled 开关）
        assertEquals(Intent.CHITCHAT, result.intent());
    }

    // ========== 补充集成场景 ==========

    @Test
    @DisplayName("DOC_OPERATION 意图应正确返回（LLM 判定 + 关键词未命中）")
    void docOperationByLlmRecognition() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"DOC_OPERATION\",\"confidence\":0.9}");

        IntentResult result = router.route("请帮我把这份文件上传到系统", List.of());

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("AMBIGUOUS 意图由 LLM 判定应正确返回")
    void ambiguousByLlmRecognition() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"AMBIGUOUS\",\"confidence\":0.6}");

        IntentResult result = router.route("那个东西", List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("confidence 恰好等于阈值 0.4 应保留 LLM 结果")
    void shouldKeepLlmResultWhenConfidenceEqualsThreshold() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.4}");

        IntentResult result = router.route("不匹配任何关键词的输入", List.of());

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("LLM 返回 CHITCHAT 且关键词也命中 chitchat → 结果应为 CHITCHAT")
    void llmAndKeywordBothChitchat() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"CHITCHAT\",\"confidence\":0.8}");

        IntentResult result = router.route("你好", List.of());

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source()); // 关键词覆盖，source 变为 RULE
    }

    @Test
    @DisplayName("LLM 判 KNOWLEDGE_QA 但输入命中 chitchat 关键词 → 覆盖为 CHITCHAT")
    void llmKnowledgeQaButKeywordChitchat() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        IntentResult result = router.route("谢谢你", List.of());

        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("LLM 判 CHITCHAT 但输入命中 doc_operation 关键词 → 覆盖为 DOC_OPERATION")
    void llmChitchatButKeywordDocOperation() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"CHITCHAT\",\"confidence\":0.8}");

        IntentResult result = router.route("删除文档", List.of());

        assertEquals(Intent.DOC_OPERATION, result.intent());
        assertEquals("RULE", result.source());
    }

    @Test
    @DisplayName("LLM 返回 markdown 包裹的 JSON 应正确解析")
    void shouldHandleMarkdownJsonFromLlm() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("```json\n{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}\n```");

        IntentResult result = router.route("不匹配关键词的输入", List.of());

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("LLM 返回包含多余文本的 JSON 应正确提取")
    void shouldExtractJsonFromExtraText() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("好的，分析结果：{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9} 完成。");

        IntentResult result = router.route("不匹配关键词的输入", List.of());

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("null 用户输入 → LLM 返回 null → 关键词不命中 → AMBIGUOUS")
    void nullInputFullFallback() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = router.route(null, List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
        assertEquals("KEYWORD", result.source());
    }

    @Test
    @DisplayName("空字符串输入 → LLM 返回 null → 关键词不命中 → AMBIGUOUS")
    void emptyInputFullFallback() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = router.route("", List.of());

        assertEquals(Intent.AMBIGUOUS, result.intent());
    }

    @Test
    @DisplayName("knowledge_qa 关键词命中但 LLM 判 CHITCHAT → 覆盖为 KNOWLEDGE_QA")
    void keywordKnowledgeQaOverridesLlmChitchat() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"CHITCHAT\",\"confidence\":0.8}");

        IntentResult result = router.route("查询一下", List.of());

        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals("RULE", result.source());
    }
}
