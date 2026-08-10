package com.zyh.archivemind.service;

import com.zyh.archivemind.model.ParsedDocument;
import com.zyh.archivemind.parser.DocumentParser;
import com.zyh.archivemind.parser.ParseRequest;
import com.zyh.archivemind.parser.ParseResult;
import com.zyh.archivemind.parser.ParserRouter;
import com.zyh.archivemind.repository.ParsedDocumentRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
public class ParsedDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(ParsedDocumentService.class);
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STORAGE_MINIO = "MINIO";
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown; charset=UTF-8";

    private final ParsedDocumentRepository parsedDocumentRepository;
    private final ParserRouter parserRouter;
    private final MinioClient minioClient;
    private final boolean cacheEnabled;
    private final boolean forceReparse;
    private final String parsedBucket;
    private final String tempDir;

    public ParsedDocumentService(
            ParsedDocumentRepository parsedDocumentRepository,
            ParserRouter parserRouter,
            MinioClient minioClient,
            @Value("${parsing.cache.enabled:true}") boolean cacheEnabled,
            @Value("${parsing.force-reparse:false}") boolean forceReparse,
            @Value("${parsing.cache.bucket-name:${minio.bucketName:uploads}}") String parsedBucket,
            @Value("${file.parsing.temp-dir:${java.io.tmpdir}/archivemind}") String tempDir) {
        this.parsedDocumentRepository = parsedDocumentRepository;
        this.parserRouter = parserRouter;
        this.minioClient = minioClient;
        this.cacheEnabled = cacheEnabled;
        this.forceReparse = forceReparse;
        this.parsedBucket = parsedBucket;
        this.tempDir = tempDir;
    }

    /**
     * 获取可复用 Markdown 引用；缓存未命中时执行解析并保存解析结果。
     * 本次处理使用返回的 localPath，长期缓存由 objectKey 指向 MinIO。
     */
    public ParsedMarkdownRef getOrParseRef(ParseRequest request) {
        DocumentParser parser = parserRouter.resolve(request);
        String configHash = parser.parserConfigHash(request);
        boolean reparse = forceReparse || request.forceReparse();

        if (cacheEnabled && !reparse) {
            Optional<ParsedDocument> cached = parsedDocumentRepository
                    .findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashAndParseStatusOrderByUpdatedAtDesc(
                            request.fileMd5(), parser.parserType(), parser.parserVersion(), configHash, STATUS_SUCCESS);
            if (cached.isPresent() && isCacheObjectUsable(cached.get())) {
                ParsedDocument document = cached.get();
                Path localMarkdown = downloadCachedMarkdown(document);
                logger.info("解析缓存命中: fileMd5={}, parserType={}, parserVersion={}, objectKey={}, markdownBytes={}",
                        request.fileMd5(), parser.parserType(), parser.parserVersion(),
                        document.getMarkdownObjectKey(), document.getMarkdownBytes());
                return new ParsedMarkdownRef(
                        localMarkdown,
                        document.getMarkdownObjectKey(),
                        document.getMarkdownBytes(),
                        document.getContentHash(),
                        true);
            }
        }

        logger.info("解析缓存未命中，开始解析: fileMd5={}, fileName={}, parserType={}, parserVersion={}, forceReparse={}",
                request.fileMd5(), request.normalizedFileName(), parser.parserType(), parser.parserVersion(), reparse);

        try {
            ParseResult result = parser.parse(request);
            validateParseResult(request, result);

            String objectKey = null;
            if (cacheEnabled) {
                objectKey = saveSuccessResult(request, parser, result);
                logger.info("解析结果已缓存到对象存储: fileMd5={}, parserType={}, parserVersion={}, objectKey={}, markdownBytes={}",
                        request.fileMd5(), parser.parserType(), parser.parserVersion(),
                        objectKey, result.markdownBytes());
            } else {
                logger.info("解析缓存已禁用，本次不写入 parsed_documents: fileMd5={}, parserType={}",
                        request.fileMd5(), parser.parserType());
            }

            return new ParsedMarkdownRef(
                    result.markdownPath(),
                    objectKey,
                    result.markdownBytes(),
                    result.contentHash(),
                    false);
        } catch (RuntimeException e) {
            if (cacheEnabled) {
                saveFailedResult(request, parser, e.getMessage());
            }
            throw e;
        }
    }

    private void validateParseResult(ParseRequest request, ParseResult result) {
        if (result == null || result.markdownPath() == null) {
            throw new IllegalStateException("解析结果为空: fileName=" + request.normalizedFileName());
        }
        if (!Files.exists(result.markdownPath())) {
            throw new IllegalStateException("解析结果文件不存在: " + result.markdownPath());
        }
        if (result.markdownBytes() <= 0) {
            throw new IllegalStateException("解析结果为空: fileName=" + request.normalizedFileName());
        }
    }

    private boolean isCacheObjectUsable(ParsedDocument document) {
        if (!STATUS_SUCCESS.equals(document.getParseStatus())) {
            return false;
        }
        if (document.getMarkdownObjectKey() == null || document.getMarkdownObjectKey().isBlank()) {
            return false;
        }
        if (document.getMarkdownBytes() <= 0) {
            return false;
        }

        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(parsedBucket)
                            .object(document.getMarkdownObjectKey())
                            .build());
            boolean usable = stat.size() == document.getMarkdownBytes();
            if (!usable) {
                logger.warn("解析缓存对象大小不一致: objectKey={}, dbBytes={}, objectBytes={}",
                        document.getMarkdownObjectKey(), document.getMarkdownBytes(), stat.size());
            }
            return usable;
        } catch (Exception e) {
            logger.warn("解析缓存对象不可用: objectKey={}, error={}",
                    document.getMarkdownObjectKey(), e.getMessage());
            return false;
        }
    }

    private Path downloadCachedMarkdown(ParsedDocument document) {
        try {
            Files.createDirectories(Paths.get(tempDir));
            Path target = Files.createTempFile(Paths.get(tempDir), "parsed-cache-", ".md");
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(parsedBucket)
                            .object(document.getMarkdownObjectKey())
                            .build())) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("下载解析缓存失败: objectKey=" + document.getMarkdownObjectKey(), e);
        }
    }

    private String saveSuccessResult(ParseRequest request, DocumentParser parser, ParseResult result) {
        String configHash = parser.parserConfigHash(request);
        String objectKey = buildObjectKey(request, parser, configHash);
        uploadMarkdown(result.markdownPath(), result.markdownBytes(), objectKey);

        ParsedDocument document = findExisting(request, parser, configHash)
                .orElseGet(ParsedDocument::new);
        fillCommonFields(document, request, parser, configHash);
        document.setContentHash(result.contentHash());
        document.setMarkdownLength(toSafeInt(result.markdownBytes()));
        document.setMarkdownBytes(result.markdownBytes());
        document.setMarkdownObjectKey(objectKey);
        document.setStorageType(STORAGE_MINIO);
        document.setTitle(result.title());
        document.setLanguage(result.language());
        document.setParseStatus(STATUS_SUCCESS);
        document.setErrorMessage(null);

        saveDocument(request, parser, document);
        return objectKey;
    }

    private void saveFailedResult(ParseRequest request, DocumentParser parser, String errorMessage) {
        String configHash = parser.parserConfigHash(request);
        Optional<ParsedDocument> existing = findExisting(request, parser, configHash);

        if (existing.isPresent() && STATUS_SUCCESS.equals(existing.get().getParseStatus())) {
            logger.warn("解析失败但已有成功缓存，保留旧缓存: fileMd5={}, parserType={}, parserVersion={}, error={}",
                    request.fileMd5(), parser.parserType(), parser.parserVersion(), errorMessage);
            return;
        }

        ParsedDocument document = existing.orElseGet(ParsedDocument::new);
        fillCommonFields(document, request, parser, configHash);
        document.setContentHash(DigestUtils.sha256Hex(""));
        document.setMarkdownLength(0);
        document.setMarkdownBytes(0);
        document.setMarkdownObjectKey(null);
        document.setStorageType(STORAGE_MINIO);
        document.setTitle(null);
        document.setLanguage(request.language());
        document.setParseStatus(STATUS_FAILED);
        document.setErrorMessage(errorMessage);

        saveDocument(request, parser, document);
    }

    private Optional<ParsedDocument> findExisting(ParseRequest request, DocumentParser parser, String configHash) {
        return parsedDocumentRepository
                .findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashOrderByUpdatedAtDesc(
                        request.fileMd5(), parser.parserType(), parser.parserVersion(), configHash);
    }

    private void fillCommonFields(ParsedDocument document, ParseRequest request,
                                  DocumentParser parser, String configHash) {
        document.setFileMd5(request.fileMd5());
        document.setParserType(parser.parserType());
        document.setParserVersion(parser.parserVersion());
        document.setParserConfigHash(configHash);
        document.setFileName(request.normalizedFileName());
        document.setFileExtension(request.extension());
    }

    private void uploadMarkdown(Path markdownPath, long markdownBytes, String objectKey) {
        try (InputStream inputStream = Files.newInputStream(markdownPath)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(parsedBucket)
                            .object(objectKey)
                            .stream(inputStream, markdownBytes, -1)
                            .contentType(MARKDOWN_CONTENT_TYPE)
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException("上传解析 Markdown 到对象存储失败: objectKey=" + objectKey, e);
        }
    }

    private void saveDocument(ParseRequest request, DocumentParser parser, ParsedDocument document) {
        try {
            parsedDocumentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            logger.warn("解析缓存写入发生唯一键冲突，可能已有并发任务写入相同缓存: fileMd5={}, parserType={}, parserVersion={}",
                    request.fileMd5(), parser.parserType(), parser.parserVersion());
        }
    }

    private String buildObjectKey(ParseRequest request, DocumentParser parser, String configHash) {
        return "parsed-documents/"
                + sanitizeSegment(request.fileMd5()) + "/"
                + sanitizeSegment(parser.parserType()) + "/"
                + sanitizeSegment(parser.parserVersion()) + "/"
                + sanitizeSegment(configHash) + ".md";
    }

    private String sanitizeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._=-]", "_");
    }

    private int toSafeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private void deleteTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                logger.warn("无法删除解析 Markdown 临时文件: {}, 原因: {}", path, e.getMessage());
            }
        }
    }
}
