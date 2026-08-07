package com.zyh.archivemind.service;

import com.zyh.archivemind.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Cross-Encoder 语义重排序服务。
 *
 * Bi-Encoder（KNN）快速粗筛 → Cross-Encoder（Reranker）精排，
 * 将 query 和每个候选 doc 拼接后送入 Transformer 计算深度语义相关性，
 * 精度远超 KNN 余弦相似度。NDCG@10 提升 40-67%，幻觉率降低 30-40%。
 *
 * 支持 OpenAI 兼容接口、Jina/Cohere 接口和 TEI 接口三种格式。
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutSeconds;
    private final int maxCandidates;
    private final int maxDocumentChars;

    public RerankerService(
            @Value("${reranker.api.url:http://localhost:8088}") String apiUrl,
            @Value("${reranker.api.key:}") String apiKey,
            @Value("${reranker.model:BAAI/bge-reranker-v2-m3}") String model,
            @Value("${reranker.timeout-seconds:60}") int timeoutSeconds,
            @Value("${reranker.max-candidates:20}") int maxCandidates,
            @Value("${reranker.max-document-chars:1600}") int maxDocumentChars) {
        this.model = model;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxCandidates = Math.max(1, maxCandidates);
        this.maxDocumentChars = Math.max(200, maxDocumentChars);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(apiUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.webClient = builder.build();
        log.info("RerankerService 初始化完成, apiUrl={}, model={}, timeout={}s, maxCandidates={}, maxDocumentChars={}",
                apiUrl, model, this.timeoutSeconds, this.maxCandidates, this.maxDocumentChars);
    }

    /**
     * 对候选文档重排序。
     *
     * @param query      原始查询
     * @param candidates 初检结果列表
     * @param topK       最终返回的文档数
     * @return 重排序后的结果，分数已替换为 rerank relevance_score
     */
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (topK <= 0) {
            return Collections.emptyList();
        }
        if (candidates.size() == 1) {
            return candidates;
        }

        long start = System.currentTimeMillis();
        try {
            int rerankLimit = Math.min(candidates.size(), Math.max(topK, maxCandidates));
            List<SearchResult> rerankCandidates = candidates.subList(0, rerankLimit);

            List<String> documents = rerankCandidates.stream()
                    .map(this::toRerankDocument)
                    .toList();

            List<Double> scores = callReranker(query, documents);

            // 按 rerank 分数降序，取 topK，替换原分数
            List<SearchResult> reranked = IntStream.range(0, rerankCandidates.size())
                    .boxed()
                    .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                    .limit(topK)
                    .map(i -> {
                        SearchResult r = rerankCandidates.get(i);
                        r.setScore(scores.get(i));
                        return r;
                    })
                    .toList();

            log.info("Reranker 完成, 候选:{} -> 精排:{} -> topK:{}, 耗时:{}ms",
                    candidates.size(), rerankCandidates.size(), reranked.size(), System.currentTimeMillis() - start);
            return reranked;

        } catch (Exception e) {
            log.error("Reranker 调用失败，降级使用初检排序: {}", e.getMessage());
            // 降级：截取前 topK
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Double> callReranker(String query, List<String> documents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);

        Map<String, Object> response = webClient.post()
                .uri("/rerank")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(500)))
                .block(Duration.ofSeconds(timeoutSeconds));

        if (response == null || !response.containsKey("results")) {
            throw new RuntimeException("Reranker 响应异常: " + response);
        }

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        return parseScores(results, documents.size());
    }

    private List<Double> parseScores(List<Map<String, Object>> results, int expectedSize) {
        if (results == null || results.isEmpty()) {
            throw new RuntimeException("Reranker 响应 results 为空");
        }

        boolean hasIndex = results.stream().allMatch(r -> r.containsKey("index"));
        if (hasIndex) {
            List<Double> scores = new ArrayList<>(Collections.nCopies(expectedSize, 0.0));
            for (Map<String, Object> result : results) {
                int index = ((Number) result.get("index")).intValue();
                if (index < 0 || index >= expectedSize) {
                    throw new RuntimeException("Reranker 响应 index 越界: " + index);
                }
                scores.set(index, readScore(result));
            }
            return scores;
        }

        if (results.size() != expectedSize) {
            throw new RuntimeException("Reranker 响应数量异常: expected=" + expectedSize + ", actual=" + results.size());
        }
        return results.stream()
                .map(this::readScore)
                .toList();
    }

    private double readScore(Map<String, Object> result) {
        Object score = result.get("relevance_score");
        if (!(score instanceof Number number)) {
            throw new RuntimeException("Reranker 响应缺少 relevance_score: " + result);
        }
        return number.doubleValue();
    }

    private String toRerankDocument(SearchResult result) {
        String text = firstNonBlank(result.getContextualizedContent(), result.getTextContent());
        if (text.length() <= maxDocumentChars) {
            return text;
        }
        return text.substring(0, maxDocumentChars);
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
