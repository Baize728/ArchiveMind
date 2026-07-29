package com.zyh.archivemind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别专用轻量 LLM 客户端。
 * 独立于 RewriteLlmClient，拥有自己的 api-url、api-key、model、timeout 配置。
 * 场景差异：改写输出自然语言句子，意图识别输出结构化 JSON（max_tokens=64 即可）。
 */
@Component
public class IntentLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(IntentLlmClient.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentLlmClient(AiProperties aiProperties) {
        AiProperties.Intent cfg = aiProperties.getIntent();

        String apiUrl = cfg.getApiUrl();
        String apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.timeoutSeconds = cfg.getTimeoutSeconds();

        if (cfg.isEnabled()) {
            if (apiUrl == null || apiUrl.isBlank()) {
                logger.warn("意图识别已启用但 ai.intent.api-url 未配置，意图识别将无法正常工作");
            }
            if (model == null || model.isBlank()) {
                logger.warn("意图识别已启用但 ai.intent.model 未配置，意图识别将无法正常工作");
            }
        }

        WebClient.Builder builder = WebClient.builder();
        if (apiUrl != null && !apiUrl.isBlank()) {
            builder.baseUrl(apiUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();

        logger.info("IntentLlmClient 初始化完成, apiUrl={}, model={}, timeout={}s",
                apiUrl, model, timeoutSeconds);
    }

    /**
     * 同步调用 LLM，返回完整的响应文本。
     *
     * @param messages OpenAI 格式的消息列表
     * @return LLM 返回的文本内容；失败时返回 null
     */
    public String chatSync(List<Map<String, String>> messages) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("temperature", 0.0);
        request.put("max_tokens", 64);

        try {
            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (responseBody == null || responseBody.isBlank()) {
                logger.warn("IntentLlmClient 收到空响应");
                return null;
            }

            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            logger.error("IntentLlmClient 同步调用失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
