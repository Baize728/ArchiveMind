package com.zyh.archivemind.service;

import com.zyh.archivemind.client.RewriteLlmClient;
import com.zyh.archivemind.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private RewriteLlmClient rewriteLlmClient;

    private AiProperties aiProperties;
    private QueryRewriteService queryRewriteService;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        queryRewriteService = new QueryRewriteService(rewriteLlmClient, aiProperties);
    }

    @Test
    @DisplayName("功能关闭时直接返回原始查询")
    void shouldReturnOriginalQueryWhenDisabled() {
        aiProperties.getRewrite().setEnabled(false);

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "iPhone 14多少钱？"),
                Map.of("role", "assistant", "content", "iPhone 14的价格是5999元起。")
        );

        String result = queryRewriteService.rewrite("那有分期吗？", history);

        assertThat(result).isEqualTo("那有分期吗？");
        verifyNoInteractions(rewriteLlmClient);
    }

    @Test
    @DisplayName("无对话历史时跳过改写")
    void shouldSkipRewriteWhenNoHistory() {
        String result = queryRewriteService.rewrite("iPhone 14多少钱？", new ArrayList<>());

        assertThat(result).isEqualTo("iPhone 14多少钱？");
        verifyNoInteractions(rewriteLlmClient);
    }

    @Test
    @DisplayName("null历史记录时跳过改写")
    void shouldSkipRewriteWhenHistoryIsNull() {
        String result = queryRewriteService.rewrite("iPhone 14多少钱？", null);

        assertThat(result).isEqualTo("iPhone 14多少钱？");
        verifyNoInteractions(rewriteLlmClient);
    }

    @Test
    @DisplayName("有历史对话时调用LLM改写并返回改写结果")
    void shouldRewriteQueryWithHistory() {
        when(rewriteLlmClient.chatSync(anyList())).thenReturn("iPhone 14有分期付款服务吗？");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "iPhone 14多少钱？"),
                Map.of("role", "assistant", "content", "iPhone 14的价格是5999元起。")
        );

        String result = queryRewriteService.rewrite("那有分期吗？", history);

        assertThat(result).isEqualTo("iPhone 14有分期付款服务吗？");
        verify(rewriteLlmClient, times(1)).chatSync(anyList());
    }

    @Test
    @DisplayName("LLM返回空结果时回退到原始查询")
    void shouldFallbackWhenLlmReturnsEmpty() {
        when(rewriteLlmClient.chatSync(anyList())).thenReturn("");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "iPhone 14多少钱？"),
                Map.of("role", "assistant", "content", "价格5999元起。")
        );

        String result = queryRewriteService.rewrite("那有分期吗？", history);
        assertThat(result).isEqualTo("那有分期吗？");
    }

    @Test
    @DisplayName("LLM返回null时回退到原始查询")
    void shouldFallbackWhenLlmReturnsNull() {
        when(rewriteLlmClient.chatSync(anyList())).thenReturn(null);

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "你好"),
                Map.of("role", "assistant", "content", "你好！")
        );

        String result = queryRewriteService.rewrite("再见", history);
        assertThat(result).isEqualTo("再见");
    }

    @Test
    @DisplayName("LLM调用抛异常时回退到原始查询")
    void shouldFallbackWhenLlmThrowsException() {
        when(rewriteLlmClient.chatSync(anyList())).thenThrow(new RuntimeException("API超时"));

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "什么是RAG？"),
                Map.of("role", "assistant", "content", "RAG是检索增强生成。")
        );

        String result = queryRewriteService.rewrite("怎么实现？", history);
        assertThat(result).isEqualTo("怎么实现？");
    }

    @Test
    @DisplayName("历史对话超过maxHistoryRounds时只截取最近N轮")
    void shouldTruncateHistoryToMaxRounds() {
        aiProperties.getRewrite().setMaxHistoryRounds(1);
        when(rewriteLlmClient.chatSync(anyList())).thenReturn("改写后的查询");

        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", "第一轮问题"));
        history.add(Map.of("role", "assistant", "content", "第一轮回答"));
        history.add(Map.of("role", "user", "content", "第二轮问题"));
        history.add(Map.of("role", "assistant", "content", "第二轮回答"));
        history.add(Map.of("role", "user", "content", "第三轮问题"));
        history.add(Map.of("role", "assistant", "content", "第三轮回答"));

        String result = queryRewriteService.rewrite("当前问题", history);
        assertThat(result).isEqualTo("改写后的查询");

        verify(rewriteLlmClient).chatSync(argThat(messages -> {
            String userContent = messages.get(1).get("content");
            return userContent.contains("第三轮问题")
                    && userContent.contains("第三轮回答")
                    && !userContent.contains("第一轮问题")
                    && !userContent.contains("第二轮问题");
        }));
    }

    @Test
    @DisplayName("传给LLM的消息结构正确：包含system prompt和拼接的用户消息")
    void shouldBuildCorrectLlmMessages() {
        when(rewriteLlmClient.chatSync(anyList())).thenReturn("改写结果");

        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "iPhone 14多少钱？"),
                Map.of("role", "assistant", "content", "5999元起。")
        );

        queryRewriteService.rewrite("那有分期吗？", history);

        verify(rewriteLlmClient).chatSync(argThat(messages -> {
            if (messages.size() != 2) return false;
            Map<String, String> systemMsg = messages.get(0);
            if (!"system".equals(systemMsg.get("role"))) return false;
            if (!systemMsg.get("content").contains("查询改写助手")) return false;
            Map<String, String> userMsg = messages.get(1);
            if (!"user".equals(userMsg.get("role"))) return false;
            String content = userMsg.get("content");
            return content.contains("iPhone 14多少钱")
                    && content.contains("5999元起")
                    && content.contains("那有分期吗？");
        }));
    }
}
