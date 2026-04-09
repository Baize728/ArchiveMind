package com.zyh.archivemind.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;
import com.zyh.archivemind.config.AiProperties;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }
    
    public void streamResponse(String userMessage, 
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onThinkingChunk,
                             Consumer<String> onAnswerChunk,
                             Runnable onComplete,
                             Consumer<Throwable> onError) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        
        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                    chunk -> processChunk(chunk, onThinkingChunk, onAnswerChunk, onComplete),
                    onError
                );
    }
    
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String chunk, 
                              Consumer<String> onThinkingChunk,
                              Consumer<String> onAnswerChunk,
                              Runnable onComplete) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                onComplete.run();
                return;
            }
            
            // 直接解析 JSON（复用类级别 ObjectMapper 实例）
            JsonNode node = MAPPER.readTree(chunk);
            JsonNode delta = node.path("choices").path(0).path("delta");
            
            // 提取思考过程内容（DeepSeek R1 模型特有字段）
            String reasoningContent = delta.path("reasoning_content").asText("");
            if (!reasoningContent.isEmpty()) {
                onThinkingChunk.accept(reasoningContent);
            }
            
            // 提取回答内容
            String content = delta.path("content").asText("");
            if (!content.isEmpty()) {
                onAnswerChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
} 