package com.zyh.archivemind.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zyh.archivemind.config.AiProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: ai-streaming-thinking-display, Property 1: reasoning_content 和 content 路由正确性
 *
 * Validates: Requirements 1.4
 *
 * For any non-empty reasoning_content and content, processChunk should call
 * onThinkingChunk with reasoning_content and onAnswerChunk with content respectively.
 */
class DeepSeekClientPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Creates a DeepSeekClient instance for testing.
     * Uses dummy values for API URL, key, and model since we only test processChunk via reflection.
     */
    private DeepSeekClient createClient() {
        AiProperties aiProperties = new AiProperties();
        return new DeepSeekClient("http://localhost", "test-key", "test-model", aiProperties);
    }

    /**
     * Invokes the private processChunk method via reflection.
     */
    private void invokeProcessChunk(DeepSeekClient client, String chunk,
                                    Consumer<String> onThinkingChunk,
                                    Consumer<String> onAnswerChunk,
                                    Runnable onComplete) throws Exception {
        Method method = DeepSeekClient.class.getDeclaredMethod(
                "processChunk", String.class, Consumer.class, Consumer.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(client, chunk, onThinkingChunk, onAnswerChunk, onComplete);
    }

    /**
     * Builds a mock SSE JSON chunk containing both reasoning_content and content in the delta.
     */
    private String buildChunkWithBoth(String reasoningContent, String content) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("reasoning_content", reasoningContent);
        delta.put("content", content);

        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);

        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Builds a mock SSE JSON chunk containing only reasoning_content in the delta.
     */
    private String buildChunkWithReasoningOnly(String reasoningContent) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("reasoning_content", reasoningContent);

        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);

        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Builds a mock SSE JSON chunk containing only content in the delta.
     */
    private String buildChunkWithContentOnly(String content) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("content", content);

        ObjectNode choice = MAPPER.createObjectNode();
        choice.set("delta", delta);

        root.putArray("choices").add(choice);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Feature: ai-streaming-thinking-display, Property 1: reasoning_content 和 content 路由正确性
     *
     * Validates: Requirements 1.4
     *
     * When a chunk contains both non-empty reasoning_content and content,
     * processChunk should call onThinkingChunk with reasoning_content
     * and onAnswerChunk with content.
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 1: reasoning_content 和 content 路由正确性")
    void processChunkRoutesBothFieldsCorrectly(
            @ForAll @StringLength(min = 1, max = 200) String reasoningContent,
            @ForAll @StringLength(min = 1, max = 200) String content
    ) throws Exception {
        DeepSeekClient client = createClient();
        String chunk = buildChunkWithBoth(reasoningContent, content);

        List<String> thinkingChunks = new ArrayList<>();
        List<String> answerChunks = new ArrayList<>();
        List<Boolean> completeCalls = new ArrayList<>();

        invokeProcessChunk(client, chunk,
                thinkingChunks::add,
                answerChunks::add,
                () -> completeCalls.add(true));

        assertThat(thinkingChunks).containsExactly(reasoningContent);
        assertThat(answerChunks).containsExactly(content);
        assertThat(completeCalls).isEmpty();
    }

    /**
     * Feature: ai-streaming-thinking-display, Property 1: reasoning_content 和 content 路由正确性
     *
     * Validates: Requirements 1.4
     *
     * When a chunk contains only non-empty reasoning_content (no content),
     * processChunk should call onThinkingChunk but not onAnswerChunk.
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 1: reasoning_content only routing")
    void processChunkRoutesReasoningContentOnly(
            @ForAll @StringLength(min = 1, max = 200) String reasoningContent
    ) throws Exception {
        DeepSeekClient client = createClient();
        String chunk = buildChunkWithReasoningOnly(reasoningContent);

        List<String> thinkingChunks = new ArrayList<>();
        List<String> answerChunks = new ArrayList<>();

        invokeProcessChunk(client, chunk,
                thinkingChunks::add,
                answerChunks::add,
                () -> {});

        assertThat(thinkingChunks).containsExactly(reasoningContent);
        assertThat(answerChunks).isEmpty();
    }

    /**
     * Feature: ai-streaming-thinking-display, Property 1: reasoning_content 和 content 路由正确性
     *
     * Validates: Requirements 1.4
     *
     * When a chunk contains only non-empty content (no reasoning_content),
     * processChunk should call onAnswerChunk but not onThinkingChunk.
     */
    @Property(tries = 100)
    @Label("Feature: ai-streaming-thinking-display, Property 1: content only routing")
    void processChunkRoutesContentOnly(
            @ForAll @StringLength(min = 1, max = 200) String content
    ) throws Exception {
        DeepSeekClient client = createClient();
        String chunk = buildChunkWithContentOnly(content);

        List<String> thinkingChunks = new ArrayList<>();
        List<String> answerChunks = new ArrayList<>();

        invokeProcessChunk(client, chunk,
                thinkingChunks::add,
                answerChunks::add,
                () -> {});

        assertThat(thinkingChunks).isEmpty();
        assertThat(answerChunks).containsExactly(content);
    }
}
