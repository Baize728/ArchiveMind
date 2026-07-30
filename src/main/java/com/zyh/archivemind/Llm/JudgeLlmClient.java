package com.zyh.archivemind.Llm;

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
 * Eval Judge 专用 LLM 客户端。
 * 独立配置（baseUrl / model / apiKey / timeout / maxTokens / temperature），
 * 与 IntentLlmClient 风格保持一致：WebClient 同步调用，失败返回 null。
 */
@Component
public class JudgeLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(JudgeLlmClient.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutMs;
    private final int maxTokens;
    private final double temperature;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JudgeLlmClient(AiProperties aiProperties) {
        AiProperties.Judge cfg = aiProperties.getJudge();

        String baseUrl = cfg.getBaseUrl();
        String apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.timeoutMs = cfg.getTimeoutMs();
        this.maxTokens = cfg.getMaxTokens();
        this.temperature = cfg.getTemperature();

        if (baseUrl == null || baseUrl.isBlank()) {
            logger.warn("Judge LLM baseUrl 未配置，Judge 评测将无法正常工作");
        }
        if (model == null || model.isBlank()) {
            logger.warn("Judge LLM model 未配置，Judge 评测将无法正常工作");
        }

        WebClient.Builder builder = WebClient.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();

        logger.info("JudgeLlmClient 初始化完成, baseUrl={}, model={}, timeoutMs={}, maxTokens={}, temperature={}",
                baseUrl, model, timeoutMs, maxTokens, temperature);
    }

    /**
     * 同步调用 LLM，传入单条 prompt（作为 user message），返回 LLM 响应文本。
     *
     * @param prompt 评测 prompt
     * @return LLM 返回的文本内容；失败时返回 null
     */
    public String chatSync(String prompt) {
        Map<String, String> userMsg = Map.of("role", "user", "content", prompt);
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", List.of(userMsg));
        request.put("stream", false);
        request.put("temperature", temperature);
        request.put("max_tokens", maxTokens);

        try {
            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs));

            if (responseBody == null || responseBody.isBlank()) {
                logger.warn("JudgeLlmClient 收到空响应");
                return null;
            }

            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            logger.error("JudgeLlmClient 同步调用失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
