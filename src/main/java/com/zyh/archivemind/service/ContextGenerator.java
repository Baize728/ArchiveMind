package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文前缀生成服务（Anthropic Contextual Retrieval 核心组件）。
 *
 * 为每个文本 chunk 生成 50-100 中文字的上下文描述前缀，
 * 使 chunk 脱离原始文档后仍保持语义自包含，从而提升检索精度。
 *
 * Prompt 设计源自 Anthropic 官方方案，适配 DeepSeek V3 中文环境。
 */
@Service
public class ContextGenerator {

    private static final Logger logger = LoggerFactory.getLogger(ContextGenerator.class);

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${context-generation.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${context-generation.max-retries:2}")
    private int maxRetries;

    /**
     * 上下文生成专用模型，默认 deepseek-chat（非推理模型）。
     * 不能用 deepseek-reasoner 等推理模型，因为它们输出在 reasoning_content 而非 content 字段。
     */
    private static final String DEFAULT_CONTEXT_MODEL = "deepseek-chat";

    public ContextGenerator(LlmProperties llmProperties,
                           @Value("${context-generation.model:deepseek-chat}") String contextModel) {
        LlmProperties.ProviderConfig deepseek = llmProperties.getProviders().get("deepseek");
        if (deepseek == null || !deepseek.isEnabled()) {
            throw new IllegalStateException(
                    "ContextGenerator 需要 deepseek provider 已启用，请检查 llm.providers.deepseek 配置");
        }

        String apiUrl = deepseek.getApiUrl();
        String apiKey = deepseek.getApiKey();
        this.model = contextModel;

        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("ContextGenerator 需要 llm.providers.deepseek.api-url 配置");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ContextGenerator 需要 llm.providers.deepseek.api-key 配置");
        }

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        this.webClient = builder.build();

        logger.info("ContextGenerator 初始化完成, apiUrl={}, model={}, timeout={}s",
                apiUrl, model, timeoutSeconds);
    }

    /**
     * 为单个 chunk 生成上下文前缀。
     *
     * @param documentContext 完整文档文本（用于 LLM 理解 chunk 在文档中的位置）
     * @param chunk           待增强的文本片段
     * @return 50-100 中文字的上下文描述前缀；LLM 调用失败时返回空字符串（降级）
     */
    public String generateContext(String documentContext, String chunk) {
        List<Map<String, String>> messages = buildMessages(documentContext, chunk);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String result = doChat(messages);
                if (result != null && !result.isBlank()) {
                    logger.debug("上下文生成成功: {} chars → {} chars", chunk.length(), result.length());
                    return result;
                }
            } catch (Exception e) {
                logger.warn("上下文生成失败 (attempt {}/{}): {}", attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * (attempt + 1)); // 递增退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        logger.warn("上下文生成降级: 返回空字符串");
        return "";
    }

    private List<Map<String, String>> buildMessages(String documentContext, String chunk) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content",
                "你是一个文档分析助手。你的任务是为给定的文本片段生成简洁的上下文描述，" +
                "以便该片段在脱离原始文档后仍能被准确检索。");
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", String.format(
                "<document>\n%s\n</document>\n\n" +
                "以下是该文档中的一个文本片段，请为其生成简洁的上下文描述，" +
                "以便将该片段在文档中的位置和作用描述清楚，从而提高检索精度。\n\n" +
                "<chunk>\n%s\n</chunk>\n\n" +
                "请仅输出 1-2 句中文上下文描述（50-100字），其他内容不要输出。\n" +
                "上下文描述应包括：文档主题/标题、该片段所属章节、关键实体（人物/公司/时间/事件）。",
                truncateDocument(documentContext), chunk));
        messages.add(userMsg);

        return messages;
    }

    private String doChat(List<Map<String, String>> messages) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("temperature", 0.0);
        request.put("max_tokens", 200);

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class)
                                    .map(body -> new IllegalStateException(
                                            "LLM API 返回错误状态 " + resp.statusCode() + ": " + body)))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));
        } catch (Exception e) {
            logger.error("LLM API 调用网络异常: {}", e.getMessage());
            return null;
        }

        if (responseBody == null || responseBody.isBlank()) {
            logger.warn("LLM API 返回空响应体");
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode message = node.path("choices").path(0).path("message");
            String content = message.path("content").asText("").trim();

            // 防御：推理模型（如 deepseek-v4-flash）输出在 reasoning_content 而非 content
            if (content.isEmpty()) {
                String reasoning = message.path("reasoning_content").asText("").trim();
                if (!reasoning.isEmpty()) {
                    logger.debug("LLM 返回 reasoning_content ({} chars), 用作上下文前缀", reasoning.length());
                    // reasoning_content 可能很长，只取最后一段作为上下文描述
                    return reasoning.length() > 200 ? reasoning.substring(reasoning.length() - 200).trim() : reasoning;
                }
                logger.warn("LLM API 返回空 content 且无 reasoning_content, response 前200字: {}",
                        responseBody.substring(0, Math.min(200, responseBody.length())));
            }
            return content;
        } catch (Exception e) {
            logger.error("LLM 响应 JSON 解析失败, response 前200字: {}, 异常: {}",
                    responseBody.substring(0, Math.min(200, responseBody.length())), e.getMessage());
            return null;
        }
    }

    /**
     * 截断过长的文档上下文以保证在 LLM 上下文窗口内。
     * DeepSeek V3 128K tokens，保险起见文档部分控制在约 25 万中文字符。
     *
     * 上游 resolveDocumentUnits 已确保文档 ≤ 25 万字符后才传入，此方法为兜底安全网。
     */
    private String truncateDocument(String documentContext) {
        int maxChars = 250_000;
        if (documentContext.length() <= maxChars) {
            return documentContext;
        }
        logger.warn("文档上下文过长 ({} chars)，截断至 {} chars，请检查上游 resolveDocumentUnits 是否漏拦截",
                documentContext.length(), maxChars);
        return documentContext.substring(0, maxChars);
    }
}
