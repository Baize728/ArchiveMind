package com.zyh.archivemind.Llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * OpenAI 兼容接口的公共基类
 * DeepSeek、OpenAI、通义千问、Ollama 等都使用此格式
 */
public abstract class AbstractOpenAiCompatibleProvider implements LlmProvider {

    private static final Logger logger = LoggerFactory.getLogger(AbstractOpenAiCompatibleProvider.class);

    protected final String providerId;
    protected final WebClient webClient;
    protected final LlmProperties llmProperties;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractOpenAiCompatibleProvider(String providerId, LlmProperties llmProperties) {
        this.providerId = providerId;
        this.llmProperties = llmProperties;

        LlmProperties.ProviderConfig config = llmProperties.getProviders().get(providerId);
        WebClient.Builder builder = WebClient.builder().baseUrl(config.getApiUrl());
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    @Override
    public boolean supportsToolCalling() {
        LlmProperties.ProviderConfig config = llmProperties.getProviders().get(providerId);
        return config != null && config.isSupportsToolCalling();
    }

    @Override
    public void streamChat(LlmRequest request, LlmStreamCallback callback) {
        try {
            // 每次调用独立的缓冲区，通过闭包传递，避免 ThreadLocal 在响应式上下文中失效
            Map<Integer, ToolCallBuffer> toolCallBuffers = new HashMap<>();
            Map<String, Object> apiRequest = buildApiRequest(request);

            webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(apiRequest)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processStreamChunk(chunk, callback, toolCallBuffers),
                            error -> {
                                logger.error("[{}] API 调用失败: {}", providerId, error.getMessage(), error);
                                callback.onError(error);
                            },
                            () -> {
                                flushToolCalls(callback, toolCallBuffers);
                                callback.onComplete();
                            }
                    );
        } catch (Exception e) {
            logger.error("[{}] 构建请求失败: {}", providerId, e.getMessage(), e);
            callback.onError(e);
        }
    }

    private Map<String, Object> buildApiRequest(LlmRequest request) {
        Map<String, Object> apiRequest = new HashMap<>();

        LlmProperties.ProviderConfig config = llmProperties.getProviders().get(providerId);
        String model = request.getModel() != null ? request.getModel() : config.getModel();
        apiRequest.put("model", model);

        // 消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("role", msg.getRole());

            if (msg.getContent() != null) {
                msgMap.put("content", msg.getContent());
            }
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                msgMap.put("tool_call_id", msg.getToolCallId());
            }
            if (msg.getToolCall() != null) {
                ToolCall tc = msg.getToolCall();
                Map<String, Object> toolCallMap = new HashMap<>();
                toolCallMap.put("id", tc.getId());
                toolCallMap.put("type", "function");
                toolCallMap.put("function", Map.of(
                        "name", tc.getFunctionName(),
                        "arguments", tc.getArguments()
                ));
                msgMap.put("tool_calls", List.of(toolCallMap));
            }
            messages.add(msgMap);
        }
        apiRequest.put("messages", messages);
        apiRequest.put("stream", true);

        // 工具定义
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDefinition tool : request.getTools()) {
                tools.add(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.getName(),
                                "description", tool.getDescription(),
                                "parameters", tool.getParameters()
                        )
                ));
            }
            apiRequest.put("tools", tools);
        }

        // 生成参数
        GenerationParams params = request.getParams();
        if (params == null) {
            LlmProperties.GenerationConfig gen = llmProperties.getGeneration();
            apiRequest.put("temperature", gen.getTemperature());
            apiRequest.put("max_tokens", gen.getMaxTokens());
            apiRequest.put("top_p", gen.getTopP());
        } else {
            if (params.getTemperature() != null) apiRequest.put("temperature", params.getTemperature());
            if (params.getMaxTokens() != null)   apiRequest.put("max_tokens", params.getMaxTokens());
            if (params.getTopP() != null)         apiRequest.put("top_p", params.getTopP());
        }

        return apiRequest;
    }

    private void processStreamChunk(String chunk, LlmStreamCallback callback,
                                    Map<Integer, ToolCallBuffer> toolCallBuffers) {
        try {
            if ("[DONE]".equals(chunk.trim())) return;

            JsonNode node = objectMapper.readTree(chunk);
            JsonNode delta = node.path("choices").path(0).path("delta");

            String content = delta.path("content").asText("");
            if (!content.isEmpty()) {
                callback.onTextChunk(content);
            }

            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    ToolCallBuffer buffer = toolCallBuffers.computeIfAbsent(index, k -> new ToolCallBuffer());

                    String id = tc.path("id").asText(null);
                    if (id != null) buffer.id = id;

                    String funcName = tc.path("function").path("name").asText(null);
                    if (funcName != null) buffer.functionName = funcName;

                    String args = tc.path("function").path("arguments").asText("");
                    buffer.argumentsBuilder.append(args);
                }
            }
        } catch (Exception e) {
            logger.error("[{}] 处理流式数据块出错: {}", providerId, e.getMessage(), e);
        }
    }

    private void flushToolCalls(LlmStreamCallback callback, Map<Integer, ToolCallBuffer> toolCallBuffers) {
        if (toolCallBuffers.isEmpty()) return;

        for (ToolCallBuffer buffer : toolCallBuffers.values()) {
            if (buffer.functionName != null) {
                ToolCall toolCall = ToolCall.builder()
                        .id(buffer.id)
                        .functionName(buffer.functionName)
                        .arguments(buffer.argumentsBuilder.toString())
                        .build();
                logger.info("[{}] 检测到工具调用: id={}, function={}, arguments={}",
                        providerId, toolCall.getId(), toolCall.getFunctionName(), toolCall.getArguments());
                callback.onToolCall(toolCall);
            }
        }
    }

    private static class ToolCallBuffer {
        String id;
        String functionName;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }
}
