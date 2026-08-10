package com.zyh.archivemind.consumer;


import com.zyh.archivemind.service.ParseService;
import com.zyh.archivemind.service.VectorizationService;
import com.zyh.archivemind.config.KafkaConfig;
import com.zyh.archivemind.model.FileProcessingTask;
import com.zyh.archivemind.service.LlamaParseClient.LlamaParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;

@Service
@Slf4j
public class FileProcessingConsumer {

    private static final Duration PROCESSING_LOCK_TTL = Duration.ofMinutes(30);

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final StringRedisTemplate stringRedisTemplate;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService,
                                  StringRedisTemplate stringRedisTemplate) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        String userId = normalizeUserId(task.getUserId());
        String lockKey = buildProcessingLockKey(task.getFileMd5(), userId);
        String lockValue = UUID.randomUUID().toString();
        InputStream fileStream = null;

        if (!acquireProcessingLock(lockKey, lockValue)) {
            log.warn("同一文件正在处理中，跳过重复任务: fileMd5={}, userId={}", task.getFileMd5(), userId);
            return;
        }

        try {
            // 下载文件（方法内部失败会直接抛异常，不会返回 null）
            fileStream = downloadFileFromStorage(task.getFilePath());

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件
            parseService.parseAndSave(task.getFileMd5(), fileStream, 
                    task.getFileName(), userId, task.getOrgTag(), task.isPublic());
            log.info("文件解析完成，fileMd5: {}", task.getFileMd5());

            // 向量化处理
            vectorizationService.vectorize(task.getFileMd5(), 
                    userId, task.getOrgTag(), task.isPublic());
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
        } catch (Exception e) {
            log.error("Error processing task: {}", task, e);
            // 区分异常类型：
            // 不可重试（认证/参数/文件超限）→ 直接抛原异常，DefaultErrorHandler 将其路由到 DLT
            // 可重试（网络/IO 瞬时故障）→ 包装为 RuntimeException 触发 Kafka 重试
            if (isNonRetryable(e)) {
                throw e instanceof RuntimeException re ? re : new RuntimeException("不可重试的处理失败", e);
            }
            throw new RuntimeException("Error processing task（可重试）", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
            releaseProcessingLock(lockKey, lockValue);
        }
    }

    private boolean acquireProcessingLock(String lockKey, String lockValue) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, PROCESSING_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            throw new RuntimeException("获取文件处理锁失败: " + lockKey, e);
        }
    }

    private void releaseProcessingLock(String lockKey, String lockValue) {
        try {
            String currentValue = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(currentValue)) {
                stringRedisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("释放文件处理锁失败: {}, 原因: {}", lockKey, e.getMessage());
        }
    }

    private String buildProcessingLockKey(String fileMd5, String userId) {
        return "processing:" + fileMd5 + ":" + userId;
    }

    private String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? "unknown" : userId;
    }

    /**
     * 从存储系统下载文件。
     *
     * @param filePath 文件路径、本地路径或远程 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws IOException {
        log.info("Downloading file from storage: {}", filePath);

        // 如果是文件系统路径
        File file = new File(filePath);
        if (file.exists()) {
            log.info("Detected file system path: {}", filePath);
            return new FileInputStream(file);
        }

        // 如果是远程 URL
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            log.info("Detected remote URL: {}", filePath);
            URL url = new URL(filePath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000); // 连接超时30秒
            connection.setReadTimeout(180000);   // 读取超时时间3分钟

            // 添加必要的请求头
            connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                log.info("Successfully connected to URL, starting download...");
                return connection.getInputStream();
            } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                log.error("Access forbidden - possible expired presigned URL");
                throw new IOException("Access forbidden - the presigned URL may have expired");
            } else {
                log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
            }
        }

        // 如果既不是文件路径也不是 URL
        throw new IllegalArgumentException("Unsupported file path format: " + filePath);
    }

    /**
     * 判断异常是否为不可重试类型。
     * 不可重试的异常直接路由到 DLT，避免无效的 Kafka 重试循环。
     */
    private boolean isNonRetryable(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = (root.getMessage() != null) ? root.getMessage() : "";

        // LlamaParse 客户端异常中不可重试的子类
        if (e instanceof LlamaParseException) {
            return msg.contains("HTTP 401") || msg.contains("HTTP 403")
                    || msg.contains("HTTP 400") || msg.contains("HTTP 413")
                    || msg.contains("HTTP 404");
        }
        // MinIO 下载失败：HTTP 403（预签名 URL 过期）/ HTTP 404（文件不存在），重试无意义
        if (msg.contains("Failed to download file, HTTP response code: 403")
                || msg.contains("Failed to download file, HTTP response code: 404")
                || msg.contains("Access forbidden")) {
            return true;
        }
        // 参数校验类异常，重试无意义
        if (e instanceof IllegalArgumentException) return true;
        // 不支持的文件格式
        if (msg.contains("Unsupported file")) return true;

        return false;
    }
}
