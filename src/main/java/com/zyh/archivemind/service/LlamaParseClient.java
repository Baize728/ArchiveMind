package com.zyh.archivemind.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

    @Value("${llamaparse.language:ch_sim}")
    private String language;

    @Value("${llamaparse.tier:agentic}")
    private String tier;

    @Value("${llamaparse.version:latest}")
    private String version;

    @Value("${llamaparse.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${llamaparse.poll-interval-seconds:3}")
    private int pollIntervalSeconds;

    @Value("${llamaparse.poll-max-interval-seconds:15}")
    private int pollMaxIntervalSeconds;

    @Value("${llamaparse.poll-backoff-multiplier:1.5}")
    private double pollBackoffMultiplier;

    @Value("${llamaparse.poll-jitter-factor:0.2}")
    private double pollJitterFactor;

    @Value("${llamaparse.poll-max-status-errors:5}")
    private int pollMaxStatusErrors;

    @Value("${llamaparse.status-request-timeout-seconds:30}")
    private int statusRequestTimeoutSeconds;

    @Value("${llamaparse.result-request-timeout-seconds:120}")
    private int resultRequestTimeoutSeconds;

    public LlamaParseClient(
            @Value("${llamaparse.api-key}") String apiKey,
            @Value("${llamaparse.api-url:https://api.cloud.llamaindex.ai/api/v2}") String apiUrl,
            @Value("${llamaparse.timeout-seconds:300}") int timeoutSeconds) {
        String normalizedApiUrl = normalizeApiUrl(apiUrl);
        // 配置 Netty 级超时：connect / read / write / response 全覆盖
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                           .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        this.webClient = WebClient.builder()
                .baseUrl(normalizedApiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        logger.info("LlamaParseClient 初始化完成, apiUrl={}, timeout={}s", normalizedApiUrl, timeoutSeconds);
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
        int maxRetries = 3;
        long backoffMs = 2000L;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                MultipartBodyBuilder builder = new MultipartBodyBuilder();
                builder.part("file", new FileSystemResource(filePath));
                builder.part("configuration", buildConfigurationJson());

                String response = webClient.post()
                        .uri("/parse/upload")
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
                logger.info("LlamaParse 上传成功, jobId: {} (attempt {})", jobId, attempt + 1);
                return jobId;

            } catch (LlamaParseException e) {
                // 不可重试的错误（认证失败、参数错误、文件超限等）直接抛出
                if (isNonRetryable(e)) {
                    throw e;
                }
                if (attempt == maxRetries) {
                    logger.error("LlamaParse 上传重试耗尽 ({} 次), 文件: {}", maxRetries + 1, filePath);
                    throw e;
                }
                long waitMs = backoffMs * (1L << attempt); // 2s, 4s, 8s
                logger.warn("LlamaParse 上传失败 (attempt {}), {}ms 后重试: {}", attempt + 1, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlamaParseException("上传重试被中断", ie);
                }
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new LlamaParseException("上传文件异常（重试" + (maxRetries + 1) + "次后仍失败）: " + e.getMessage(), e);
                }
                long waitMs = backoffMs * (1L << attempt);
                logger.warn("LlamaParse 上传异常 (attempt {}), {}ms 后重试: {}", attempt + 1, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlamaParseException("上传重试被中断", ie);
                }
            }
        }

        // 不可达，但编译器需要
        throw new LlamaParseException("上传文件失败: 未知错误");
    }

    /**
     * 判断 LlamaParseException 是否为不可重试的错误。
     * HTTP 4xx（除 429）通常是客户端问题，重试无意义。
     */
    private boolean isNonRetryable(LlamaParseException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        // 认证/授权失败
        if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) return true;
        // 文件过大
        if (msg.contains("HTTP 413")) return true;
        // 请求参数错误
        if (msg.contains("HTTP 400")) return true;
        // v2 参数校验失败
        if (msg.contains("HTTP 422")) return true;
        // 资源不存在
        if (msg.contains("HTTP 404")) return true;
        return false;
    }

    // ===================== Step 2: Poll =====================

    private void waitForCompletion(String jobId) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int attempt = 0;
        int consecutiveStatusErrors = 0;
        long delayMs = initialPollDelayMs();

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                JobStatus jobStatus = fetchJobStatus(jobId);
                consecutiveStatusErrors = 0;
                String status = jobStatus.status();
                logger.debug("LlamaParse job {} 状态: {} (attempt {})", jobId, status, attempt);

                switch (status.toUpperCase()) {
                    case "SUCCESS":
                    case "COMPLETED":
                        logger.info("LlamaParse job {} 解析完成 ({} attempts)", jobId, attempt);
                        return;
                    case "PENDING":
                    case "PROCESSING":
                    case "RUNNING":
                        delayMs = sleepBeforeNextPoll(jobId, status, attempt, delayMs, deadline);
                        break;
                    case "ERROR":
                    case "FAILED":
                    case "CANCELLED":
                        throw new LlamaParseException(
                                "LlamaParse 解析失败, jobId=" + jobId + ", status=" + status
                                        + ", error=" + jobStatus.errorMessage());
                    default:
                        logger.warn("LlamaParse job {} 未知状态: {}, 继续轮询", jobId, status);
                        delayMs = sleepBeforeNextPoll(jobId, status, attempt, delayMs, deadline);
                }
            } catch (LlamaParseException e) {
                throw e;
            } catch (Exception e) {
                consecutiveStatusErrors++;
                logger.warn("LlamaParse 轮询异常 (attempt {}, consecutiveErrors={}): {}",
                        attempt, consecutiveStatusErrors, e.getMessage());
                if (consecutiveStatusErrors >= Math.max(1, pollMaxStatusErrors)) {
                    throw new LlamaParseException(
                            "LlamaParse 状态查询连续失败 " + consecutiveStatusErrors + " 次, jobId=" + jobId, e);
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new LlamaParseException(
                            "LlamaParse 轮询超时 (" + timeoutSeconds + "s), jobId=" + jobId, e);
                }
                delayMs = sleepBeforeNextPoll(jobId, "STATUS_ERROR", attempt, delayMs, deadline);
            }
        }

        throw new LlamaParseException(
                "LlamaParse 解析超时 (" + timeoutSeconds + "s), jobId=" + jobId);
    }

    private JobStatus fetchJobStatus(String jobId) throws Exception {
        String response = webClient.get()
                .uri("/parse/" + jobId)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(statusRequestTimeoutSeconds));

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("获取 job 状态失败: 返回空响应, jobId=" + jobId);
        }

        JsonNode job = objectMapper.readTree(response).path("job");
        return new JobStatus(
                job.path("status").asText("UNKNOWN"),
                job.path("error_message").asText("")
        );
    }

    private long sleepBeforeNextPoll(String jobId, String status, int attempt,
                                     long currentDelayMs, long deadline) {
        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return currentDelayMs;
        }

        long sleepMs = Math.min(applyJitter(currentDelayMs), remainingMs);
        logger.debug("LlamaParse job {} 未完成, status={}, attempt={}, {}ms 后再次轮询",
                jobId, status, attempt, sleepMs);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlamaParseException("轮询被中断, jobId=" + jobId, e);
        }
        return nextPollDelayMs(currentDelayMs);
    }

    private long initialPollDelayMs() {
        return Math.max(1000L, pollIntervalSeconds * 1000L);
    }

    private long maxPollDelayMs() {
        return Math.max(initialPollDelayMs(), pollMaxIntervalSeconds * 1000L);
    }

    private long nextPollDelayMs(long currentDelayMs) {
        double multiplier = pollBackoffMultiplier > 1.0 ? pollBackoffMultiplier : 1.0;
        long nextDelay = (long) Math.ceil(currentDelayMs * multiplier);
        return Math.min(nextDelay, maxPollDelayMs());
    }

    private long applyJitter(long delayMs) {
        double factor = Math.max(0.0, Math.min(pollJitterFactor, 1.0));
        if (factor == 0.0) {
            return delayMs;
        }
        double min = 1.0 - factor;
        double max = 1.0 + factor;
        return Math.max(1L, Math.round(delayMs * ThreadLocalRandom.current().nextDouble(min, max)));
    }

    // ===================== Step 3: Fetch Result =====================

    /**
     * 获取 Markdown 解析结果。
     * 官方 v2 API 通过 GET /parse/{job_id}?expand=markdown_full 返回完整 Markdown。
     */
    private String fetchResult(String jobId) {
        String expandValue = resolveExpandValue();
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/parse/{jobId}")
                            .queryParam("expand", expandValue)
                            .build(jobId))
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            resp -> resp.bodyToMono(String.class)
                                    .map(body -> new LlamaParseException(
                                            "获取解析结果失败: HTTP " + resp.statusCode() + " - " + body)))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(resultRequestTimeoutSeconds));

            if (response == null || response.isBlank()) {
                throw new LlamaParseException("获取解析结果失败: 返回空响应, jobId=" + jobId);
            }

            JsonNode node = objectMapper.readTree(response);
            String status = node.path("job").path("status").asText("UNKNOWN");
            if (!isCompletedStatus(status)) {
                throw new LlamaParseException("LlamaParse job 未完成, jobId=" + jobId + ", status=" + status);
            }

            String result = node.path(expandValue).asText();
            if (result == null || result.isBlank()) {
                throw new LlamaParseException("解析结果为空, jobId=" + jobId + ", expand=" + expandValue);
            }
            logger.info("LlamaParse 结果获取成功, jobId: {}, 文本长度: {} chars", jobId, result.length());
            return result;
        } catch (LlamaParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LlamaParseException("获取解析结果异常: " + e.getMessage(), e);
        }
    }

    private String buildConfigurationJson() throws Exception {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("tier", tier);
        configuration.put("version", version);

        String normalizedLanguage = normalizeLanguage(language);
        if (normalizedLanguage != null && !normalizedLanguage.isBlank()) {
            Map<String, Object> processingOptions = new LinkedHashMap<>();
            Map<String, Object> ocrParameters = new LinkedHashMap<>();
            ocrParameters.put("languages", List.of(normalizedLanguage));
            processingOptions.put("ocr_parameters", ocrParameters);
            configuration.put("processing_options", processingOptions);
        }

        Map<String, Object> processingControl = new LinkedHashMap<>();
        Map<String, Object> timeouts = new LinkedHashMap<>();
        timeouts.put("base_in_seconds", timeoutSeconds);
        timeouts.put("extra_time_per_page_in_seconds", 30);
        processingControl.put("timeouts", timeouts);
        configuration.put("processing_control", processingControl);

        return objectMapper.writeValueAsString(configuration);
    }

    private String resolveExpandValue() {
        if ("text".equalsIgnoreCase(resultType)) {
            return "text_full";
        }
        return "markdown_full";
    }

    private boolean isCompletedStatus(String status) {
        return "COMPLETED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status);
    }

    private String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return "https://api.cloud.llamaindex.ai/api/v2";
        }
        String normalized = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (normalized.endsWith("/api/v1")) {
            return normalized.substring(0, normalized.length() - "/api/v1".length()) + "/api/v2";
        }
        return normalized;
    }

    private String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "zh", "zh-cn", "zh_cn", "cn", "chinese", "chinese-simplified" -> "ch_sim";
            case "zh-tw", "zh_tw", "zh-hk", "zh_hk", "chinese-traditional" -> "ch_tra";
            default -> normalized;
        };
    }

    private record JobStatus(String status, String errorMessage) {
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
