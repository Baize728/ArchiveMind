package com.zyh.archivemind.service;

import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import com.zyh.archivemind.service.LlamaParseClient.LlamaParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

/**
 * 文档解析服务 —— Contextual Retrieval 管道入口。
 *
 * 管道：MinIO InputStream → 本地 temp file → LlamaParse API（VLM 驱动）→ 结构化 Markdown →
 *       扫描件/OCR 检测 → 结构判断 → 大小路由 → 语义切分（段落→句子→HanLP）→
 *       逐 chunk 上下文生成 → 批量入库。
 *
 * LlamaParse 替代了 Tika 提取，输出带 Markdown 标题/段落结构的文本，
 * 使现有语义切分管线能正确检测到段落边界和标题层级。
 * 扫描件由 LlamaParse VLM 自动 OCR 处理，不再直接拒收。
 */
@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    /** 自包含 chunk 跳过 LLM 上下文生成的长度阈值（≤ 200 字不调 LLM） */
    private static final int CONTEXT_MIN_CHUNK_LENGTH = 200;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ContextGenerator contextGenerator;

    @Autowired
    private LlamaParseClient llamaParseClient;

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
     * 解析文件并入库（Contextual Retrieval 管道）。
     *
     * @param fileMd5    文件的MD5哈希值
     * @param fileStream 文件输入流
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException     文件读写错误
     * @throws LlamaParseException LlamaParse 解析失败
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException {
        logger.info("开始 Contextual Retrieval 管道解析，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        Path tempFile = null;

        try {
            // Phase A: 确保 temp dir 存在
            Files.createDirectories(Paths.get(tempDir));

            // Phase B: MinIO InputStream → 本地 temp file
            tempFile = Files.createTempFile(Paths.get(tempDir), "llamaparse-in-", ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                byte[] buf = new byte[bufferSize];
                int read;
                while ((read = fileStream.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
            }
            logger.debug("输入临时文件写入完成: {} ({} bytes)", tempFile, Files.size(tempFile));

            checkMemoryThreshold();

            // Phase C: LlamaParse 云端解析 → 结构化 Markdown（替代 Tika 提取）
            String extractedText = llamaParseClient.parse(tempFile);
            logger.info("LlamaParse 解析完成, fileMd5: {}, 文本长度: {} chars", fileMd5, extractedText.length());

            // Phase D: 扫描件/低质量文档检测（仅告警，不拦截）
            // LlamaParse VLM 模式下会自动 OCR 扫描件，此检测仅用于日志监控
            if (extractedText.trim().length() < 50) {
                logger.warn("LlamaParse 提取文本极短 ({} chars)，文档可能为纯图片/空白页/非文字内容",
                        extractedText.trim().length());
            }

            // Phase E: 文档结构判断 + 大小判断 → 选择处理路径
            List<String> documentUnits = resolveDocumentUnits(extractedText);

            // 超大且无结构文档 → LLM 上下文用 CCH 固定前缀替代全文
            String llmContext = extractedText;
            if (extractedText.length() > maxTextLength
                    && !structureDetector.hasStructure(extractedText)) {
                llmContext = buildCchContext(extractedText);
                logger.info("CCH 降级: LLM 上下文切换为固定前缀 ({} chars)", llmContext.length());
            }

            // Phase F: 对每个文档单元执行语义切分 + 上下文生成 + 入库
            int totalChunks = 0;
            int chunkSeq = 0;
            for (String docUnit : documentUnits) {
                List<String> chunks = splitTextIntoChunksWithSemantics(docUnit, chunkSize);
                // 有结构/未超限：用文档原文；CCH 降级：用固定前缀
                String contextForLlm = (extractedText.length() > maxTextLength
                        && !structureDetector.hasStructure(extractedText))
                        ? llmContext : docUnit;
                chunkSeq = processChunksWithContext(
                        fileMd5, userId, orgTag, isPublic,
                        chunks, contextForLlm, chunkSeq);
                totalChunks += chunks.size();
            }

            logger.info("Contextual Retrieval 管道完成，fileMd5: {}, 总chunk数: {}", fileMd5, totalChunks);

        } catch (LlamaParseException e) {
            logger.error("LlamaParse 解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败（LlamaParse）", e);
        } finally {
            // Phase G: 清理临时文件
            deleteTempFile(tempFile);
        }
    }

    /**
     * 兼容旧版本的解析方法。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    // ===================== 文档单元拆分 =====================

    /**
     * 根据文档结构和文本长度，决定处理单元。
     * ≤ 25万字符 → 完整文档；> 25万字符且有结构 → 按章拆分子文档；> 25万字符无结构 → 全文（调用方负责切换 CCH 上下文）。
     *
     * @return 文档单元列表（每个元素独立走切分+上下文生成）
     */
    private List<String> resolveDocumentUnits(String fullText) {
        if (fullText.length() <= maxTextLength) {
            logger.debug("文档大小 {} 字，未超限，完整文档走标准路径", fullText.length());
            return List.of(fullText);
        }

        boolean hasStructure = structureDetector.hasStructure(fullText);
        logger.info("文档超限 ({} > {}), 结构检测: {}", fullText.length(), maxTextLength,
                hasStructure ? "有结构-按章拆分" : "无结构-CCH降级");

        if (hasStructure) {
            List<String> chapters = structureDetector.splitByChapters(fullText);
            logger.info("按章节拆分为 {} 个子文档", chapters.size());
            return chapters;
        }

        // CCH 降级：由调用方 parseAndSave 负责将 llmContext 替换为 CCH 固定前缀
        return List.of(fullText);
    }

    /**
     * CCH（Contextual Chunk Headers）降级上下文。
     * 当文档无章节结构时，从 Markdown 文本中提取元信息作为固定前缀，零 LLM 开销。
     */
    private String buildCchContext(String fullText) {
        StringBuilder sb = new StringBuilder();
        // 从 LlamaParse Markdown 输出中提取一级标题作为文档标题
        Pattern h1Pattern = Pattern.compile("(?m)^#[^#].*");
        Matcher m = h1Pattern.matcher(fullText);
        if (m.find()) {
            sb.append("文档标题: ").append(m.group().substring(1).trim()).append("。");
        }
        // 提取二级标题作为章节概览
        Pattern h2Pattern = Pattern.compile("(?m)^##[^#].*");
        Matcher m2 = h2Pattern.matcher(fullText);
        List<String> sections = new ArrayList<>();
        while (m2.find()) {
            sections.add(m2.group().substring(2).trim());
        }
        if (!sections.isEmpty()) {
            sb.append("包含章节: ").append(String.join("、", sections.subList(0, Math.min(5, sections.size()))));
            if (sections.size() > 5) {
                sb.append("等");
            }
            sb.append("。");
        }
        // 提取第一个一级编号标题作为补充
        Pattern numH1 = Pattern.compile("(?m)^\\d+\\.(?!\\d)");
        Matcher m3 = numH1.matcher(fullText);
        if (m3.find() && sections.isEmpty()) {
            sb.append("首个章节: ").append(m3.group().trim()).append("。");
        }
        return sb.toString();
    }

    // ===================== 上下文生成 + 入库 =====================

    /**
     * 对 chunk 列表逐条生成上下文前缀（如需要），然后批量写入 MySQL。
     *
     * @param fileMd5         文件指纹
     * @param userId          用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param chunks          文本 chunk 列表
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
     * LlamaParse Markdown 输出已包含段落边界（\n\n），
     * 此方法按 段落 → 句子 → HanLP 分词的层级递进切分。
     *
     * @param text      LlamaParse 返回的结构化 Markdown 文本
     * @param chunkSize chunk 目标大小（字符数）
     * @return chunk 列表
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                continue;
            }

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
