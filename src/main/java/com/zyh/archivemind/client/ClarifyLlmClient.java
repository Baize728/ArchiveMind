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
 * 澄清层共用 LLM 客户端（T1-2，Q17）。
 *
 * 被 SlotExtractor（LLM fallback）和 ClarifyAgentService（追问生成）共用。
 * max_tokens 和 temperature 由调用方传参：
 * - SlotExtractor fallback: maxTokens=256, temperature=0
 * - ClarifyAgentService:    maxTokens=128, temperature=0.7
 *
 * 配置归属 ai.clarify.*（与 ai.intent.* / ai.rewrite.* 独立）。
 */
@Component
public class ClarifyLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(ClarifyLlmClient.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClarifyLlmClient(AiProperties aiProperties) {
        AiProperties.Clarify cfg = aiProperties.getClarify();

        String baseUrl = cfg.getBaseUrl();
        String apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.timeoutMs = cfg.getTimeoutMs();

        if (cfg.isEnabled()) {
            if (baseUrl == null || baseUrl.isBlank()) {
                logger.warn("澄清层已启用但 ai.clarify.base-url 未配置");
            }
            if (model == null || model.isBlank()) {
                logger.warn("澄清层已启用但 ai.clarify.model 未配置");
            }
        }

        WebClient.Builder builder = WebClient.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();

        logger.info("ClarifyLlmClient 初始化完成, baseUrl={}, model={}, timeout={}ms",
                baseUrl, model, timeoutMs);
    }

    /**
     * 同步调用 LLM，max_tokens 和 temperature 由调用方传参。
     *
     * @param messages    OpenAI 格式消息列表
     * @param maxTokens   最大输出 token 数
     * @param temperature 采样温度
     * @return LLM 返回的文本内容；失败时返回 null
     */
    public String chatSync(List<Map<String, String>> messages, int maxTokens, double temperature) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
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
                logger.warn("ClarifyLlmClient 收到空响应");
                return null;
            }

            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            logger.error("ClarifyLlmClient 同步调用失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
