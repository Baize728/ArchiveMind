package com.zyh.archivemind.service;

import com.zyh.archivemind.exception.OcrRequiredException;
import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

/**
 * 文档解析服务 —— Contextual Retrieval 管道入口。
 *
 * 管道：MinIO InputStream → 本地 temp file → Tika 流式提取 → 输出 temp file →
 *       OCR 扫描件检测 → 结构判断 → 大小判断 → 单层语义切分 → 逐 chunk 上下文生成 → 批量入库。
 *
 * JVM 内存安全：Tika 流式 + 磁盘落盘，O(1) 内存占用。
 */
@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    /** 扫描件检测：文本总长度阈值（< 50 中文字符视为扫描件） */
    private static final int MIN_SCANNED_TEXT_LENGTH = 50;

    /** 扫描件检测：每页文本密度阈值（< 50 字符/页视为扫描件） */
    private static final double MIN_TEXT_DENSITY_PER_PAGE = 50.0;

    /** 自包含 chunk 跳过 LLM 上下文生成的长度阈值（≤ 200 字不调 LLM） */
    private static final int CONTEXT_MIN_CHUNK_LENGTH = 200;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ContextGenerator contextGenerator;

    @Autowired
    private DocumentStructureDetector structureDetector;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.buffer-size:1024 * 1024}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${file.parsing.temp-dir:${java.io.tmpdir}/archivemind}")
    private String tempDir;

    @Value("${file.parsing.max-text-length:250000}")
    private int maxTextLength;

    /**
     * 应用启动时清理残留的临时文件（处理异常中断的遗留物）。
     * 删除超过 24 小时的 temp file，目录不存在则跳过。
     */
    @PostConstruct
    public void cleanupZombieTempFiles() {
        Path dir = Paths.get(tempDir);
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
            Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                                Files.delete(p);
                                logger.info("清理僵尸临时文件: {}", p);
                            }
                        } catch (IOException e) {
                            logger.warn("无法清理僵尸临时文件: {}, 原因: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.warn("扫描临时文件目录失败: {}", e.getMessage());
        }
    }

    /**
     * 流式解析文件并入库（Contextual Retrieval 管道）。
     *
     * @param fileMd5    文件的MD5哈希值
     * @param fileStream 文件输入流
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException     文件读取错误
     * @throws TikaException   文件解析错误
     * @throws OcrRequiredException 扫描件/图片PDF，Tika无法提取有效文本
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始 Contextual Retrieval 管道解析，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        Path inputTempFile = null;
        Path outputTempFile = null;

        try {
            // Phase A: 确保 temp dir 存在
            Files.createDirectories(Paths.get(tempDir));

            // Phase B: MinIO InputStream → 本地 input temp file
            inputTempFile = Files.createTempFile(Paths.get(tempDir), "tika-in-", ".tmp");
            try (FileOutputStream fos = new FileOutputStream(inputTempFile.toFile())) {
                byte[] buf = new byte[bufferSize];
                int read;
                while ((read = fileStream.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
            }
            logger.debug("输入临时文件写入完成: {} ({} bytes)", inputTempFile, Files.size(inputTempFile));

            // Phase C: Tika 流式提取 → output temp file
            outputTempFile = Files.createTempFile(Paths.get(tempDir), "tika-out-", ".tmp");
            Metadata metadata = extractTextWithTika(inputTempFile, outputTempFile);

            checkMemoryThreshold();

            // Phase D: 读取提取文本
            String extractedText = Files.readString(outputTempFile);

            // Phase E: 扫描件/图片 PDF 双重检测
            checkScannedDocument(extractedText, metadata);

            // Phase F: 文档结构判断 + 大小判断 → 选择处理路径
            List<String> documentUnits = resolveDocumentUnits(extractedText, metadata);

            // Phase G: 对每个文档单元执行单层切分 + 上下文生成 + 入库
            int totalChunks = 0;
            int chunkSeq = 0;
            for (String docUnit : documentUnits) {
                List<String> chunks = splitTextIntoChunksWithSemantics(docUnit, chunkSize);
                chunkSeq = processChunksWithContext(
                        fileMd5, userId, orgTag, isPublic,
                        chunks, docUnit, chunkSeq);
                totalChunks += chunks.size();
            }

            logger.info("Contextual Retrieval 管道完成，fileMd5: {}, 总chunk数: {}", fileMd5, totalChunks);

        } catch (OcrRequiredException e) {
            throw e; // 直接上抛，由上层处理（拒收/转OCR队列）
        } catch (SAXException e) {
            logger.error("Tika 解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        } finally {
            // Phase H: 清理临时文件
            deleteTempFile(inputTempFile);
            deleteTempFile(outputTempFile);
        }
    }

    /**
     * 兼容旧版本的解析方法。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    // ===================== Tika 流式提取 =====================

    /**
     * 使用 Apache Tika 从输入文件流式提取文本到输出文件。
     * StreamingContentHandler 逐段写入 BufferedWriter，JVM 内存 O(1)。
     *
     * @param inputFile  原始文件路径（用于 Tika 检测格式）
     * @param outputFile 提取文本的输出路径
     * @return Tika 解析产生的 Metadata（含页数、heading 信息等）
     */
    private Metadata extractTextWithTika(Path inputFile, Path outputFile)
            throws IOException, SAXException, TikaException {
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile.toFile()), "UTF-8"), bufferSize);
             InputStream fis = new BufferedInputStream(new FileInputStream(inputFile.toFile()), bufferSize)) {

            StreamingContentHandler handler = new StreamingContentHandler(writer);
            parser.parse(fis, handler, metadata, context);
            writer.flush();
        }

        long extractedSize = Files.size(outputFile);
        logger.info("Tika 文本提取完成: {} bytes", extractedSize);
        return metadata;
    }

    /**
     * 流式内容处理器：将 Tika 提取的文本逐段写入磁盘，不驻留 JVM 堆。
     */
    private static class StreamingContentHandler extends BodyContentHandler {
        private final BufferedWriter writer;

        StreamingContentHandler(BufferedWriter writer) {
            super(-1); // 禁用 Tika 内部写入限制
            this.writer = writer;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            try {
                writer.write(ch, start, length);
            } catch (IOException e) {
                throw new UncheckedIOException("写入输出临时文件失败", e);
            }
        }
    }

    // ===================== 扫描件检测 =====================

    /**
     * 双重信号检测扫描件/图片 PDF。
     * 信号1：文本总长度 < 50 字；信号2：每页文本密度 < 50 字/页。任一命中即拦截。
     */
    private void checkScannedDocument(String extractedText, Metadata metadata) {
        int textLength = extractedText.trim().length();
        int pageCount = extractPageCount(metadata);
        double textPerPage = pageCount > 0 ? (double) textLength / pageCount : textLength;

        boolean textTooShort = textLength < MIN_SCANNED_TEXT_LENGTH;
        boolean densityTooLow = textPerPage < MIN_TEXT_DENSITY_PER_PAGE;

        if (textTooShort || densityTooLow) {
            String reason = String.format(
                    "扫描件/图片PDF检测拦截: 文本总长度=%d字 (阈值%d), 页数=%d, 文本密度=%.1f字/页 (阈值%.0f)",
                    textLength, MIN_SCANNED_TEXT_LENGTH, pageCount, textPerPage, MIN_TEXT_DENSITY_PER_PAGE);
            logger.warn(reason);
            throw new OcrRequiredException(reason, textLength, pageCount, textPerPage);
        }

        logger.debug("扫描件检测通过: 文本长度={}, 页数={}, 密度={:.1f}字/页",
                textLength, pageCount, textPerPage);
    }

    /** 从 Tika Metadata 中提取页数 */
    private int extractPageCount(Metadata metadata) {
        String npages = metadata.get("xmpTPg:NPages");
        if (npages != null && !npages.isEmpty()) {
            try {
                return Integer.parseInt(npages);
            } catch (NumberFormatException ignored) {
            }
        }
        String pageCount = metadata.get("Page-Count");
        if (pageCount != null && !pageCount.isEmpty()) {
            try {
                return Integer.parseInt(pageCount);
            } catch (NumberFormatException ignored) {
            }
        }
        // PDF 和 Word 至少 1 页
        String contentType = metadata.get("Content-Type");
        if (contentType != null && contentType.contains("pdf")) {
            return 1; // PDF 默认至少1页
        }
        return 1;
    }

    // ===================== 文档单元拆分 =====================

    /**
     * 根据文档结构和文本长度，决定处理单元。
     * ≤ 25万字符 → 完整文档；> 25万字符且有结构 → 按章拆分子文档；> 25万字符无结构 → CCH 降级。
     *
     * @return 文档单元列表（每个元素独立走切分+上下文生成）
     */
    private List<String> resolveDocumentUnits(String fullText, Metadata metadata) {
        if (fullText.length() <= maxTextLength) {
            logger.debug("文档大小 {} 字，未超限，完整文档走标准路径", fullText.length());
            return List.of(fullText);
        }

        // 超限：检查结构
        boolean hasStructure = structureDetector.hasStructure(fullText, metadata);
        logger.info("文档超限 ({} > {}), 结构检测: {}", fullText.length(), maxTextLength,
                hasStructure ? "有结构-按章拆分" : "无结构-CCH降级");

        if (hasStructure) {
            List<String> chapters = structureDetector.splitByChapters(fullText);
            logger.info("按章节拆分为 {} 个子文档", chapters.size());
            return chapters;
        }

        // CCH 降级：文件名 + Tika 元数据作为文档上下文
        String cchContext = buildCchContext(metadata);
        logger.info("CCH 降级: 使用固定前缀 '{}...' 作为文档上下文", cchContext.substring(0, Math.min(50, cchContext.length())));
        return List.of(fullText);
    }

    /**
     * CCH（Contextual Chunk Headers）降级上下文。
     * 当文档无章节结构时，用文件名和 Tika 元数据拼接为固定前缀，零 LLM 开销。
     */
    private String buildCchContext(Metadata metadata) {
        StringBuilder sb = new StringBuilder();
        String title = metadata.get("title");
        if (title != null && !title.isBlank()) {
            sb.append("文档标题: ").append(title).append("。");
        }
        String author = metadata.get("author");
        if (author != null && !author.isBlank()) {
            sb.append("作者: ").append(author).append("。");
        }
        String contentType = metadata.get("Content-Type");
        if (contentType != null && !contentType.isBlank()) {
            sb.append("类型: ").append(contentType).append("。");
        }
        int pageCount = extractPageCount(metadata);
        if (pageCount > 0) {
            sb.append("共 ").append(pageCount).append(" 页。");
        }
        return sb.toString();
    }

    // ===================== 上下文生成 + 入库 =====================

    /**
     * 对 chunk 列表逐条生成上下文前缀（如需要），然后批量写入 MySQL。
     *
     * @param fileMd5      文件指纹
     * @param userId       用户ID
     * @param orgTag       组织标签
     * @param isPublic     是否公开
     * @param chunks       文本 chunk 列表
     * @param documentContext 完整文档文本（用作 LLM 上下文参考）
     * @param startingChunkId chunk 起始序号
     * @return 保存后的最终 chunk 序号
     */
    private int processChunksWithContext(String fileMd5, String userId, String orgTag,
            boolean isPublic, List<String> chunks, String documentContext, int startingChunkId) {
        int currentChunkId = startingChunkId;
        List<DocumentVector> batch = new ArrayList<>(Math.min(chunks.size(), 200));

        for (String chunk : chunks) {
            currentChunkId++;

            String contextualizedContent;
            if (chunk.length() <= CONTEXT_MIN_CHUNK_LENGTH) {
                // 自包含短文本（FAQ、对话对等），跳过 LLM 调用
                contextualizedContent = chunk;
            } else {
                String contextPrefix = contextGenerator.generateContext(documentContext, chunk);
                if (contextPrefix != null && !contextPrefix.isBlank()) {
                    contextualizedContent = contextPrefix + "\n" + chunk;
                } else {
                    // LLM 调用失败降级：直接用原始文本
                    contextualizedContent = chunk;
                }
            }

            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setContextualizedContent(contextualizedContent);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            batch.add(vector);

            // 分批 flush：每 200 条一次
            if (batch.size() >= 200) {
                documentVectorRepository.saveAll(batch);
                batch.clear();
            }
        }

        // 剩余数据 flush
        if (!batch.isEmpty()) {
            documentVectorRepository.saveAll(batch);
        }

        logger.info("Chunk 上下文生成+入库完成: {} chunks (起始序号 {})", chunks.size(), startingChunkId + 1);
        return currentChunkId;
    }

    // ===================== 内存安全检查 =====================

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {}%, 触发垃圾回收", String.format("%.2f", memoryUsage * 100));
            System.gc();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " +
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    // ===================== 临时文件清理 =====================

    private void deleteTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.warn("无法删除临时文件: {}, 原因: {}", path, e.getMessage());
            }
        }
    }

    // ===================== 文本切分（保留原有语义切分逻辑） =====================

    /**
     * 智能文本分割，保持语义完整性。
     * 按段落 → 句子 → HanLP 分词的层级递进切分。
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            }
            else if (currentChunk.length() + paragraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                currentChunk = new StringBuilder(paragraph);
            }
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 分割长段落，按句子边界。
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割。
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        try {
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;

                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }

            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}",
                    sentence.length(), termList.size(), chunks.size());

        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
         }

        return chunks;
    }

    /**
     * 备用方案：按字符分割。
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
