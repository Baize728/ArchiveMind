package com.zyh.archivemind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.service.EmbeddingCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端（含 Redis 缓存）
@Component
public class EmbeddingClient {

    @Value("${embedding.api.model}")
    private String modelId;

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    @Value("${embedding.api.dimension:2048}")
    private int dimension;

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingCacheService cacheService;

    public EmbeddingClient(WebClient embeddingWebClient, ObjectMapper objectMapper,
                           EmbeddingCacheService cacheService) {
        this.webClient = embeddingWebClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    /**
     * 生成向量（Redis L1 缓存优先）。
     * 先查缓存，未命中的通过 API 批量生成后回填缓存。
     */
    public List<float[]> embed(List<String> texts) {
        try {
            logger.info("开始生成向量（含缓存检查），文本数量: {}", texts.size());

            // 1. 批量查缓存
            float[][] cached = cacheService.batchGet(texts);
            List<Integer> missIdx = new ArrayList<>();
            List<String> missTexts = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                if (cached[i] == null) {
                    missIdx.add(i);
                    missTexts.add(texts.get(i));
                }
            }

            // 2. 未命中则调 API
            if (!missTexts.isEmpty()) {
                List<float[]> apiResults = new ArrayList<>();
                for (int start = 0; start < missTexts.size(); start += batchSize) {
                    int end = Math.min(start + batchSize, missTexts.size());
                    List<String> batch = missTexts.subList(start, end);
                    logger.debug("调用向量 API, batch: {}/{}", end, missTexts.size());
                    String response = callApiOnce(batch);
                    apiResults.addAll(parseVectors(response));
                }
                // 回填缓存
                cacheService.batchPut(missTexts, apiResults);

                // 把 API 结果填入返回数组
                for (int j = 0; j < missIdx.size(); j++) {
                    cached[missIdx.get(j)] = apiResults.get(j);
                }
            }

            // 3. 组装最终结果
            List<float[]> all = new ArrayList<>(texts.size());
            for (float[] vec : cached) all.add(vec);
            logger.info("向量生成完成，总数量: {}", all.size());
            return all;
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量生成失败", e);
        }
    }

    private String callApiOnce(List<String> batch) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("input", batch);
        requestBody.put("dimension", dimension);  // 直接在根级别设置dimension
        requestBody.put("encoding_format", "float");  // 添加编码格式

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException))
                .block(Duration.ofSeconds(30));
    }

    private List<float[]> parseVectors(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
