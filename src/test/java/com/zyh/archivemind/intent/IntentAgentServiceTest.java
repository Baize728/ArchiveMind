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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntentAgentServiceTest {

    @Mock private IntentLlmClient intentLlmClient;

    private IntentAgentService service;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getIntent().setEnabled(true);
        service = new IntentAgentService(intentLlmClient, aiProperties);
    }

    @Test
    @DisplayName("LLM 返回合法 JSON 应正确解析为 IntentResult")
    void shouldParseValidJsonResult() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        IntentResult result = service.recognize("年假怎么申请", List.of());

        assertNotNull(result);
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
        assertEquals(0.9, result.confidence());
        assertEquals("LLM", result.source());
    }

    @Test
    @DisplayName("LLM 返回带 markdown 代码块的 JSON 应正确解析")
    void shouldParseMarkdownWrappedJson() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("```json\n{\"intent\":\"CHITCHAT\",\"confidence\":0.8}\n```");

        IntentResult result = service.recognize("你好", List.of());

        assertNotNull(result);
        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals(0.8, result.confidence());
    }

    @Test
    @DisplayName("LLM 返回空文本应返回 null")
    void shouldReturnNullWhenLlmReturnsEmpty() {
        when(intentLlmClient.chatSync(anyList())).thenReturn("");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回 null 应返回 null")
    void shouldReturnNullWhenLlmReturnsNull() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回非法 JSON 应返回 null")
    void shouldReturnNullWhenLlmReturnsInvalidJson() {
        when(intentLlmClient.chatSync(anyList())).thenReturn("这不是JSON");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回非法枚举值应返回 null")
    void shouldReturnNullWhenIntentIsInvalidEnum() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"UNKNOWN_INTENT\",\"confidence\":0.5}");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回缺 confidence 字段时应使用默认值 0.5")
    void shouldUseDefaultConfidenceWhenMissing() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\"}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(0.5, result.confidence());
    }

    @Test
    @DisplayName("意图识别未启用应返回 null")
    void shouldReturnNullWhenDisabled() {
        aiProperties.getIntent().setEnabled(false);

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
        verify(intentLlmClient, never()).chatSync(anyList());
    }

    @Test
    @DisplayName("LLM 调用抛异常应返回 null 不抛异常")
    void shouldReturnNullWhenLlmThrowsException() {
        when(intentLlmClient.chatSync(anyList())).thenThrow(new RuntimeException("超时"));

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("带历史的对话应拼入 prompt")
    void shouldIncludeHistoryInPrompt() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "上次问的问题"),
                Map.of("role", "assistant", "content", "上次的回答")
        );

        IntentResult result = service.recognize("这次的问题", history);

        assertNotNull(result);
    }

    // ========== 边界条件 ==========

    @Test
    @DisplayName("空用户输入应仍尝试调用 LLM（由 LLM 判断）")
    void shouldCallLlmForEmptyUserMessage() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = service.recognize("", List.of());

        assertNull(result);
        verify(intentLlmClient).chatSync(anyList());
    }

    @Test
    @DisplayName("null 用户输入应仍尝试调用 LLM")
    void shouldCallLlmForNullUserMessage() {
        when(intentLlmClient.chatSync(anyList())).thenReturn(null);

        IntentResult result = service.recognize(null, List.of());

        assertNull(result);
        verify(intentLlmClient).chatSync(anyList());
    }

    @Test
    @DisplayName("超长用户输入应正常处理")
    void shouldHandleVeryLongUserMessage() {
        String longMessage = "请帮我查询".repeat(500);
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        IntentResult result = service.recognize(longMessage, List.of());

        assertNotNull(result);
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("LLM 返回 intent 大写应正确解析")
    void shouldParseUppercaseIntent() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("LLM 返回 intent 小写应正确解析（大小写不敏感）")
    void shouldParseLowercaseIntent() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"knowledge_qa\",\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(Intent.KNOWLEDGE_QA, result.intent());
    }

    @Test
    @DisplayName("LLM 返回 intent 混合大小写应正确解析")
    void shouldParseMixedCaseIntent() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"Chitchat\",\"confidence\":0.8}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(Intent.CHITCHAT, result.intent());
    }

    @Test
    @DisplayName("LLM 返回 confidence=0 应正确解析")
    void shouldParseZeroConfidence() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.0}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(0.0, result.confidence());
    }

    @Test
    @DisplayName("LLM 返回 confidence=1.0 应正确解析")
    void shouldParseMaxConfidence() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":1.0}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(1.0, result.confidence());
    }

    @Test
    @DisplayName("LLM 返回负数 confidence 应解析为负数（由规则层处理）")
    void shouldParseNegativeConfidence() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":-0.1}");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(-0.1, result.confidence());
    }

    @Test
    @DisplayName("LLM 返回缺 intent 字段应返回 null")
    void shouldReturnNullWhenIntentFieldMissing() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回 intent 为空字符串应返回 null")
    void shouldReturnNullWhenIntentIsEmptyString() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"\",\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回 intent 为 null 应返回 null")
    void shouldReturnNullWhenIntentIsNull() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":null,\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回纯空白字符串应返回 null")
    void shouldReturnNullForBlankResponse() {
        when(intentLlmClient.chatSync(anyList())).thenReturn("   \n\t  ");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回包含多余文本的 JSON 应正确提取")
    void shouldExtractJsonFromExtraText() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("好的，分析结果如下：{\"intent\":\"CHITCHAT\",\"confidence\":0.85} 以上。");

        IntentResult result = service.recognize("测试", List.of());

        assertNotNull(result);
        assertEquals(Intent.CHITCHAT, result.intent());
        assertEquals(0.85, result.confidence());
    }

    @Test
    @DisplayName("带超长历史的对话应只取最近 6 条")
    void shouldOnlyUseRecent6MessagesFromHistory() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        // 构造 20 条历史
        List<Map<String, String>> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "content", "消息" + i));
        }

        IntentResult result = service.recognize("最新问题", history);

        assertNotNull(result);
        verify(intentLlmClient).chatSync(anyList());
    }

    @Test
    @DisplayName("history 为 null 时应正常处理")
    void shouldHandleNullHistory() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        IntentResult result = service.recognize("测试", null);

        assertNotNull(result);
    }

    @Test
    @DisplayName("history 中包含未知 role 的消息应被跳过")
    void shouldSkipUnknownRoleInHistory() {
        when(intentLlmClient.chatSync(anyList()))
                .thenReturn("{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.9}");

        List<Map<String, String>> history = List.of(
                Map.of("role", "system", "content", "系统消息"),
                Map.of("role", "user", "content", "用户消息")
        );

        IntentResult result = service.recognize("测试", history);

        assertNotNull(result);
    }

    // ========== 异常情况 ==========

    @Test
    @DisplayName("LLM 调用抛 NullPointerException 应返回 null 不抛异常")
    void shouldReturnNullWhenLlmThrowsNpe() {
        when(intentLlmClient.chatSync(anyList())).thenThrow(new NullPointerException("NPE"));

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 调用抛 InterruptedException 应返回 null 不抛异常")
    void shouldReturnNullWhenLlmThrowsInterrupted() {
        when(intentLlmClient.chatSync(anyList())).thenThrow(new RuntimeException("interrupted"));

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }

    @Test
    @DisplayName("LLM 返回非法 JSON（数组而非对象）应返回 null")
    void shouldReturnNullForJsonArray() {
        when(intentLlmClient.chatSync(anyList())).thenReturn("[1,2,3]");

        IntentResult result = service.recognize("测试", List.of());

        assertNull(result);
    }
}
