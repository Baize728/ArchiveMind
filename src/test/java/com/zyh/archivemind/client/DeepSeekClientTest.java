package com.zyh.archivemind.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zyh.archivemind.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DeepSeekClient.processChunk 单元测试
 *
 * Validates: Requirements 1.4, 5.2
 */
class DeepSeekClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepSeekClient client;
    private List<String> thinkingChunks;
    private List<String> answerChunks;
    private List<Boolean> completeCalls;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        client = new DeepSeekClient("http://localhost", "test-key", "test-model", aiProperties);
        thinkingChunks = new ArrayList<>();
        answerChunks = new ArrayList<>();
        completeCalls = new ArrayList<>();
    }

    private void invokeProcessChunk(String chunk) throws Exception {
        Method method = DeepSeekClient.class.getDeclaredMethod(
                "processChunk", String.class, Consumer.class, Consumer.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(client, chunk, (Consumer<String>) thinkingChunks::add,
                (Consumer<String>) answerChunks::add, (Runnable) () -> completeCalls.add(true));
    }

    // --- Helper: build chunk JSON ---

    private String buildChunkWithReasoningOnly(String reasoningContent) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("reasoning_content", reasoningContent);
        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);
        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    private String buildChunkWithContentOnly(String content) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("content", content);
        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);
        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    private String buildChunkWithEmptyDelta() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);
        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    // --- Test cases ---

    @Test
    @DisplayName("仅有 reasoning_content 的 chunk → onThinkingChunk 被调用，onAnswerChunk 不被调用")
    void chunkWithOnlyReasoningContent_callsOnThinkingChunk() throws Exception {
        String chunk = buildChunkWithReasoningOnly("让我分析一下这个问题");

        invokeProcessChunk(chunk);

        assertThat(thinkingChunks).containsExactly("让我分析一下这个问题");
        assertThat(answerChunks).isEmpty();
        assertThat(completeCalls).isEmpty();
    }

    @Test
    @DisplayName("仅有 content 的 chunk → onAnswerChunk 被调用，onThinkingChunk 不被调用")
    void chunkWithOnlyContent_callsOnAnswerChunk() throws Exception {
        String chunk = buildChunkWithContentOnly("根据文档内容，答案是...");

        invokeProcessChunk(chunk);

        assertThat(answerChunks).containsExactly("根据文档内容，答案是...");
        assertThat(thinkingChunks).isEmpty();
        assertThat(completeCalls).isEmpty();
    }

    @Test
    @DisplayName("[DONE] 标记触发 onComplete")
    void doneMarker_triggersOnComplete() throws Exception {
        invokeProcessChunk("[DONE]");

        assertThat(completeCalls).hasSize(1);
        assertThat(thinkingChunks).isEmpty();
        assertThat(answerChunks).isEmpty();
    }

    @Test
    @DisplayName("空 delta（无 reasoning_content 也无 content）→ 不触发任何回调")
    void chunkWithEmptyDelta_callsNoCallbacks() throws Exception {
        String chunk = buildChunkWithEmptyDelta();

        invokeProcessChunk(chunk);

        assertThat(thinkingChunks).isEmpty();
        assertThat(answerChunks).isEmpty();
        assertThat(completeCalls).isEmpty();
    }

    @Test
    @DisplayName("无效 JSON → 不抛异常，不触发任何回调")
    void invalidJson_noExceptionNoCallbacks() {
        assertThatCode(() -> invokeProcessChunk("this is not valid json {{{"))
                .doesNotThrowAnyException();

        assertThat(thinkingChunks).isEmpty();
        assertThat(answerChunks).isEmpty();
        assertThat(completeCalls).isEmpty();
    }
}
