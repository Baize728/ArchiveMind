package com.zyh.archivemind.service;

import com.zyh.archivemind.model.ParsedDocument;
import com.zyh.archivemind.parser.DocumentParser;
import com.zyh.archivemind.parser.ParseRequest;
import com.zyh.archivemind.parser.ParseResult;
import com.zyh.archivemind.parser.ParserRouter;
import com.zyh.archivemind.repository.ParsedDocumentRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ParsedDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(ParsedDocumentService.class);
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final ParsedDocumentRepository parsedDocumentRepository;
    private final ParserRouter parserRouter;
    private final boolean cacheEnabled;
    private final boolean forceReparse;

    public ParsedDocumentService(
            ParsedDocumentRepository parsedDocumentRepository,
            ParserRouter parserRouter,
            @Value("${parsing.cache.enabled:true}") boolean cacheEnabled,
            @Value("${parsing.force-reparse:false}") boolean forceReparse) {
        this.parsedDocumentRepository = parsedDocumentRepository;
        this.parserRouter = parserRouter;
        this.cacheEnabled = cacheEnabled;
        this.forceReparse = forceReparse;
    }

    /**
     * 获取可复用 Markdown；缓存未命中时执行解析并保存解析结果。
     */
    public String getOrParse(ParseRequest request) {
        DocumentParser parser = parserRouter.resolve(request);
        String configHash = parser.parserConfigHash(request);
        boolean reparse = forceReparse || request.forceReparse();

        if (cacheEnabled && !reparse) {
            Optional<ParsedDocument> cached = parsedDocumentRepository
                    .findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashAndParseStatusOrderByUpdatedAtDesc(
                            request.fileMd5(), parser.parserType(), parser.parserVersion(), configHash, STATUS_SUCCESS);
            if (cached.isPresent() && cached.get().getMarkdownContent() != null
                    && !cached.get().getMarkdownContent().isBlank()) {
                ParsedDocument document = cached.get();
                logger.info("解析缓存命中: fileMd5={}, parserType={}, parserVersion={}, markdownLength={}",
                        request.fileMd5(), parser.parserType(), parser.parserVersion(), document.getMarkdownLength());
                return document.getMarkdownContent();
            }
        }

        logger.info("解析缓存未命中，开始解析: fileMd5={}, fileName={}, parserType={}, parserVersion={}, forceReparse={}",
                request.fileMd5(), request.normalizedFileName(), parser.parserType(), parser.parserVersion(), reparse);

        try {
            ParseResult result = parser.parse(request);
            String markdown = normalizeMarkdown(result.markdown());
            if (markdown.isBlank()) {
                throw new IllegalStateException("解析结果为空: fileName=" + request.normalizedFileName());
            }

            if (cacheEnabled) {
                saveResult(request, parser, result, markdown, STATUS_SUCCESS, null);
                logger.info("解析结果已缓存: fileMd5={}, parserType={}, parserVersion={}, markdownLength={}",
                        request.fileMd5(), parser.parserType(), parser.parserVersion(), markdown.length());
            } else {
                logger.info("解析缓存已禁用，本次不写入 parsed_documents: fileMd5={}, parserType={}",
                        request.fileMd5(), parser.parserType());
            }
            return markdown;
        } catch (RuntimeException e) {
            if (cacheEnabled) {
                saveResult(request, parser, null, "", STATUS_FAILED, e.getMessage());
            }
            throw e;
        }
    }

    private void saveResult(ParseRequest request, DocumentParser parser, ParseResult result,
                            String markdown, String status, String errorMessage) {
        String configHash = parser.parserConfigHash(request);
        Optional<ParsedDocument> existing = parsedDocumentRepository
                .findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashOrderByUpdatedAtDesc(
                        request.fileMd5(), parser.parserType(), parser.parserVersion(), configHash);

        if (STATUS_FAILED.equals(status) && existing.isPresent()
                && STATUS_SUCCESS.equals(existing.get().getParseStatus())) {
            logger.warn("解析失败但已有成功缓存，保留旧缓存: fileMd5={}, parserType={}, parserVersion={}, error={}",
                    request.fileMd5(), parser.parserType(), parser.parserVersion(), errorMessage);
            return;
        }

        ParsedDocument document = existing.orElseGet(ParsedDocument::new);

        document.setFileMd5(request.fileMd5());
        document.setParserType(parser.parserType());
        document.setParserVersion(parser.parserVersion());
        document.setParserConfigHash(configHash);
        document.setContentHash(DigestUtils.sha256Hex(markdown == null ? "" : markdown));
        document.setFileName(request.normalizedFileName());
        document.setFileExtension(request.extension());
        document.setMarkdownContent(markdown == null ? "" : markdown);
        document.setMarkdownLength(markdown == null ? 0 : markdown.length());
        document.setTitle(result != null ? result.title() : null);
        document.setLanguage(result != null ? result.language() : request.language());
        document.setParseStatus(status);
        document.setErrorMessage(errorMessage);

        try {
            parsedDocumentRepository.save(document);
        } catch (DataIntegrityViolationException e) {
            logger.warn("解析缓存写入发生唯一键冲突，可能已有并发任务写入相同缓存: fileMd5={}, parserType={}, parserVersion={}",
                    request.fileMd5(), parser.parserType(), parser.parserVersion());
        }
    }

    private String normalizeMarkdown(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown.replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
    }
}
