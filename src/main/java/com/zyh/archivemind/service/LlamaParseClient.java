package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;

/**
 * LlamaParse 云端文档解析客户端。
 *
 * LlamaParse 提供异步文档解析 API：上传文件 → 轮询 job 状态 → 拉取 Markdown 结果。
 * 解析结果保留文档结构（标题、段落、表格），扫描件自动经 VLM OCR 处理。
 */
@Service
public class LlamaParseClient {

    private static final Logger logger = LoggerFactory.getLogger(LlamaParseClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llamaparse.result-type:markdown}")
    private String resultType;

    @Value("${llamaparse.language:zh}")
    private String language;

    @Value("${llamaparse.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${llamaparse.poll-interval-seconds:3}")
    private int pollIntervalSeconds;

    public LlamaParseClient(
            @Value("${llamaparse.api-key}") String apiKey,
            @Value("${llamaparse.api-url:https://api.cloud.llamaindex.ai/api/v1}") String apiUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        logger.info("LlamaParseClient 初始化完成, apiUrl={}", apiUrl);
    }

    /**
     * 解析文件，返回 Markdown 格式的结构化文本。
     *
     * @param filePath 待解析的本地文件路径
     * @return Markdown 格式的文档内容
     * @throws LlamaParseException 解析失败（网络/API 错误 / 超时）
     */
    public String parse(Path filePath) {
        String jobId = upload(filePath);
        waitForCompletion(jobId);
        return fetchResult(jobId);
    }

    // ===================== Step 1: Upload =====================

    private String upload(Path filePath) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new FileSystemResource(filePath));
        if (resultType != null) {
            builder.part("result_type", resultType);
        }
        if (language != null) {
            builder.part("language", language);
        }

        try {
            String response = webClient.post()
                    .uri("/parsing/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            resp -> resp.bodyToMono(String.class)
                                    .map(body -> new LlamaParseException(
                                            "上传文件失败: HTTP " + resp.statusCode() + " - " + body)))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (response == null) {
                throw new LlamaParseException("上传文件失败: 返回空响应");
            }

            JsonNode node = objectMapper.readTree(response);
            String jobId = node.path("id").asText();
            if (jobId.isEmpty()) {
                throw new LlamaParseException("上传文件失败: 响应中缺少 job_id, response=" + response);
            }
            logger.info("LlamaParse 上传成功，jobId: {}", jobId);
            return jobId;
        } catch (LlamaParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LlamaParseException("上传文件异常: " + e.getMessage(), e);
        }
    }

    // ===================== Step 2: Poll =====================

    private void waitForCompletion(String jobId) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                String status = fetchJobStatus(jobId);
                logger.debug("LlamaParse job {} 状态: {} (attempt {})", jobId, status, attempt);

                switch (status.toUpperCase()) {
                    case "SUCCESS":
                    case "COMPLETED":
                        logger.info("LlamaParse job {} 解析完成 ({} attempts)", jobId, attempt);
                        return;
                    case "PENDING":
                    case "PROCESSING":
                    case "RUNNING":
                        Thread.sleep(pollIntervalSeconds * 1000L);
                        break;
                    case "ERROR":
                    case "FAILED":
                        throw new LlamaParseException(
                                "LlamaParse 解析失败, jobId=" + jobId + ", status=" + status);
                    default:
                        logger.warn("LlamaParse job {} 未知状态: {}, 继续轮询", jobId, status);
                        Thread.sleep(pollIntervalSeconds * 1000L);
                }
            } catch (LlamaParseException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlamaParseException("轮询被中断, jobId=" + jobId, e);
            } catch (Exception e) {
                logger.warn("LlamaParse 轮询异常 (attempt {}): {}", attempt, e.getMessage());
                if (System.currentTimeMillis() >= deadline) {
                    throw new LlamaParseException(
                            "LlamaParse 轮询超时 (" + timeoutSeconds + "s), jobId=" + jobId, e);
                }
                try {
                    Thread.sleep(pollIntervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlamaParseException("轮询被中断, jobId=" + jobId, ie);
                }
            }
        }

        throw new LlamaParseException(
                "LlamaParse 解析超时 (" + timeoutSeconds + "s), jobId=" + jobId);
    }

    private String fetchJobStatus(String jobId) {
        try {
            String response = webClient.get()
                    .uri("/parsing/job/" + jobId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (response == null) {
                return "UNKNOWN";
            }
            return objectMapper.readTree(response).path("status").asText("UNKNOWN");
        } catch (Exception e) {
            logger.warn("获取 job 状态失败: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    // ===================== Step 3: Fetch Result =====================

    /**
     * 获取 Markdown 解析结果。
     * 官方文档：GET /parsing/job/{job_id}/result/markdown 返回 JSON {"markdown": "...", "job_metadata": {...}}
     * 也可用 GET /parsing/job/{job_id}/raw/markdown 直接返回纯文本，省去 JSON 解析。
     */
    private String fetchResult(String jobId) {
        // 用 raw 端点直接拿纯文本，避免 JSON 解析开销
        String resultPath = "/parsing/job/" + jobId + "/raw/" + resultType;
        try {
            String result = webClient.get()
                    .uri(resultPath)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            resp -> resp.bodyToMono(String.class)
                                    .map(body -> new LlamaParseException(
                                            "获取解析结果失败: HTTP " + resp.statusCode() + " - " + body)))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(120));

            if (result == null || result.isBlank()) {
                throw new LlamaParseException("解析结果为空, jobId=" + jobId);
            }
            logger.info("LlamaParse 结果获取成功, jobId: {}, 文本长度: {} chars", jobId, result.length());
            return result;
        } catch (LlamaParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LlamaParseException("获取解析结果异常: " + e.getMessage(), e);
        }
    }

    /**
     * LlamaParse 客户端异常。
     */
    public static class LlamaParseException extends RuntimeException {
        public LlamaParseException(String message) {
            super(message);
        }

        public LlamaParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
