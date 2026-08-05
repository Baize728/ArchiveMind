package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.LlmProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private final int timeoutSeconds;
    private final int maxRetries;
    private final int maxDocumentBriefInputChars;
    private final int maxLocalContextChars;
    private final int documentBriefMaxTokens;
    private final int contextMaxTokens;
    private final long retryBaseDelayMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 上下文生成专用模型，默认 deepseek-chat（非推理模型）。
     * 不能用 deepseek-reasoner 等推理模型，因为它们输出在 reasoning_content 而非 content 字段。
     */
    private static final String DEFAULT_CONTEXT_MODEL = "deepseek-chat";

    public ContextGenerator(LlmProperties llmProperties,
                            @Value("${context-generation.model:deepseek-chat}") String contextModel,
                            @Value("${context-generation.timeout-seconds:60}") int timeoutSeconds,
                            @Value("${context-generation.max-retries:2}") int maxRetries,
                            @Value("${context-generation.connect-timeout-ms:30000}") int connectTimeoutMs,
                            @Value("${context-generation.ssl-handshake-timeout-seconds:30}") int sslHandshakeTimeoutSeconds,
                            @Value("${context-generation.max-document-brief-input-chars:${context-generation.max-document-context-chars:30000}}") int maxDocumentBriefInputChars,
                            @Value("${context-generation.max-local-context-chars:8000}") int maxLocalContextChars,
                            @Value("${context-generation.document-brief-max-tokens:800}") int documentBriefMaxTokens,
                            @Value("${context-generation.context-max-tokens:200}") int contextMaxTokens,
                            @Value("${context-generation.retry-base-delay-ms:1000}") long retryBaseDelayMs) {
        LlmProperties.ProviderConfig deepseek = llmProperties.getProviders().get("deepseek");
        if (deepseek == null || !deepseek.isEnabled()) {
            throw new IllegalStateException(
                    "ContextGenerator 需要 deepseek provider 已启用，请检查 llm.providers.deepseek 配置");
        }

        String apiUrl = deepseek.getApiUrl();
        String apiKey = deepseek.getApiKey();
        this.model = contextModel;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.maxDocumentBriefInputChars = maxDocumentBriefInputChars;
        this.maxLocalContextChars = maxLocalContextChars;
        this.documentBriefMaxTokens = documentBriefMaxTokens;
        this.contextMaxTokens = contextMaxTokens;
        this.retryBaseDelayMs = retryBaseDelayMs;

        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("ContextGenerator 需要 llm.providers.deepseek.api-url 配置");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ContextGenerator 需要 llm.providers.deepseek.api-key 配置");
        }

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .secure(ssl -> ssl.sslContext(Http11SslContextSpec.forClient())
                        .handshakeTimeout(Duration.ofSeconds(sslHandshakeTimeoutSeconds)))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        this.webClient = builder.build();

        logger.info("ContextGenerator 初始化完成, apiUrl={}, model={}, timeout={}s, connectTimeout={}ms, sslHandshakeTimeout={}s, maxBriefInputChars={}, maxLocalContextChars={}",
                apiUrl, model, timeoutSeconds, connectTimeoutMs, sslHandshakeTimeoutSeconds,
                maxDocumentBriefInputChars, maxLocalContextChars);
    }

    /**
     * 为整篇文档生成一次紧凑画像，后续每个 chunk 复用该画像，避免反复发送全文。
     */
    public String generateDocumentBrief(String documentText) {
        List<Map<String, String>> messages = buildDocumentBriefMessages(documentText);
        return callWithRetry("文档画像生成", messages, documentBriefMaxTokens);
    }

    /**
     * 为单个 chunk 生成上下文前缀。
     *
     * @param documentContext 文档或章节上下文（兼容旧调用）
     * @param chunk           待增强的文本片段
     * @return 50-100 中文字的上下文描述前缀；LLM 调用失败时返回空字符串（降级）
     */
    public String generateContext(String documentContext, String chunk) {
        return generateContext("", documentContext, chunk);
    }

    /**
     * 为单个 chunk 生成上下文前缀。
     *
     * @param documentBrief 文档级画像（主题、章节、关键实体）
     * @param localContext  chunk 所在章节或局部上下文
     * @param chunk         待增强的文本片段
     * @return 50-100 中文字的上下文描述前缀；LLM 调用失败时返回空字符串（降级）
     */
    public String generateContext(String documentBrief, String localContext, String chunk) {
        List<Map<String, String>> messages = buildContextMessages(documentBrief, localContext, chunk);
        return callWithRetry("上下文生成", messages, contextMaxTokens);
    }

    private String callWithRetry(String operation, List<Map<String, String>> messages, int maxTokens) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String result = doChat(messages, maxTokens);
                if (result != null && !result.isBlank()) {
                    logger.debug("{}成功: {} chars", operation, result.length());
                    return result;
                }
                throw new IllegalStateException("LLM API 返回空上下文");
            } catch (Exception e) {
                logger.warn("{}失败 (attempt {}/{}): {}", operation, attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryBaseDelayMs * (1L << attempt)); // 1s, 2s, 4s...
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        logger.warn("{}降级: 返回空字符串", operation);
        return "";
    }

    private List<Map<String, String>> buildDocumentBriefMessages(String documentText) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content",
                "你是一个技术文档结构分析助手。你的任务是为整篇文档生成可复用的检索增强画像。");
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", String.format(
                "<document_sample>\n%s\n</document_sample>\n\n" +
                "请生成一份用于后续 chunk 上下文增强的文档画像，控制在 600 字以内。\n" +
                "必须覆盖：文档主题、主要章节/结构、核心概念或实体、适合检索时保留的关键背景。\n" +
                "不要复述全文，不要输出 Markdown 表格。",
                truncateDocumentBriefInput(documentText)));
        messages.add(userMsg);

        return messages;
    }

    private List<Map<String, String>> buildContextMessages(String documentBrief, String localContext, String chunk) {
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
                "<document_brief>\n%s\n</document_brief>\n\n" +
                "<local_context>\n%s\n</local_context>\n\n" +
                "<chunk>\n%s\n</chunk>\n\n" +
                "以上是文档画像、该片段所在章节/局部上下文，以及当前文本片段。请为该片段生成简洁的上下文描述，" +
                "以便将该片段在文档中的位置和作用描述清楚，从而提高检索精度。\n\n" +
                "请仅输出 1-2 句中文上下文描述（50-100字），其他内容不要输出。\n" +
                "上下文描述应包括：文档主题/标题、该片段所属章节、关键实体（人物/公司/时间/事件）。",
                blankToDefault(documentBrief, "无文档画像"),
                selectLocalContext(localContext, chunk),
                chunk));
        messages.add(userMsg);

        return messages;
    }

    private String doChat(List<Map<String, String>> messages, int maxTokens) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("temperature", 0.0);
        request.put("max_tokens", maxTokens);

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
            throw new IllegalStateException("LLM API 调用网络异常: " + e.getMessage(), e);
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("LLM API 返回空响应体");
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
            throw new IllegalStateException("LLM 响应 JSON 解析失败, response 前200字: "
                    + responseBody.substring(0, Math.min(200, responseBody.length())), e);
        }
    }

    /**
     * 文档画像只生成一次，可以比单 chunk 局部上下文略长。
     */
    private String truncateDocumentBriefInput(String documentText) {
        if (documentText.length() <= maxDocumentBriefInputChars) {
            return documentText;
        }
        logger.debug("文档画像输入过长 ({} chars)，截断至 {} chars",
                documentText.length(), maxDocumentBriefInputChars);
        return documentText.substring(0, maxDocumentBriefInputChars);
    }

    /**
     * 从章节文本中截取围绕当前 chunk 的局部窗口，避免每个 chunk 反复发送整章或全文。
     */
    private String selectLocalContext(String localContext, String chunk) {
        if (localContext == null || localContext.isBlank()) {
            return "";
        }
        if (localContext.length() <= maxLocalContextChars) {
            return localContext;
        }

        int chunkIndex = localContext.indexOf(chunk);
        if (chunkIndex < 0) {
            logger.debug("无法定位 chunk，使用局部上下文开头窗口: contextLength={}, maxLocalContextChars={}",
                    localContext.length(), maxLocalContextChars);
            return localContext.substring(0, maxLocalContextChars);
        }

        int halfWindow = Math.max(maxLocalContextChars / 2, chunk.length());
        int start = Math.max(0, chunkIndex - halfWindow);
        int end = Math.min(localContext.length(), start + maxLocalContextChars);
        if (end - start < maxLocalContextChars) {
            start = Math.max(0, end - maxLocalContextChars);
        }
        return localContext.substring(start, end);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
