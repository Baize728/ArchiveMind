package com.zyh.archivemind.service;

import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构检测器。
 * 通过 Tika metadata（OOXML/HTML）或正则扫描（PDF/纯文本）判断文档是否有章节结构，
 * 并支持按章节标题拆分超大文档。
 */
@Service
public class DocumentStructureDetector {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStructureDetector.class);

    /** 视为"有结构"的最低章节标题匹配数 */
    private static final int MIN_CHAPTER_COUNT = 3;

    /** 章节标题正则模式集 */
    private static final Pattern[] CHAPTER_PATTERNS = {
        // 第N章 / 第N节 / 第N部分
        Pattern.compile("第[一二三四五六七八九十百千\\d]+[章节部分篇]"),
        // N.N / N.N.N 编号标题
        Pattern.compile("^\\d+\\.\\d+"),
        // Chapter N / Section N
        Pattern.compile("Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Section\\s+\\d+", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 综合判断文档是否有结构。
     * OOXML/HTML 类文件从 Tika metadata 获取 heading 信息；
     * PDF/纯文本类使用正则扫描章节标题模式。
     *
     * @param fullText  提取出的完整文本
     * @param metadata  Tika 解析产生的元数据
     * @return true 表示有章节结构，false 表示无结构
     */
    public boolean hasStructure(String fullText, Metadata metadata) {
        // 路径1：OOXML/HTML 等有 heading 层级的格式，从 metadata 判断
        if (hasHeadingMetadata(metadata)) {
            logger.debug("通过 Tika metadata 判断为有结构文档");
            return true;
        }

        // 路径2：PDF/纯文本，正则扫描章节标题
        int matchCount = countChapterPatterns(fullText);
        boolean hasStructure = matchCount >= MIN_CHAPTER_COUNT;
        logger.debug("正则扫描章节标题匹配数: {}, 判定有结构: {}", matchCount, hasStructure);
        return hasStructure;
    }

    /**
     * 按章节标题将文本拆分为子文档段落。
     * 仅在 hasStructure() 返回 true 时调用。
     *
     * @param fullText 完整文本
     * @return 按章节拆分的子文档列表；如果无法拆分则返回仅含 fullText 的单元素列表
     */
    public List<String> splitByChapters(String fullText) {
        // 尝试匹配 "第N章" 模式作为主要拆分点
        Pattern primaryPattern = Pattern.compile("第[一二三四五六七八九十百千\\d]+章");
        Matcher matcher = primaryPattern.matcher(fullText);

        List<Integer> splitPoints = new ArrayList<>();
        while (matcher.find()) {
            splitPoints.add(matcher.start());
        }

        if (splitPoints.size() < MIN_CHAPTER_COUNT) {
            // 尝试次级模式："第N节"
            Pattern secondaryPattern = Pattern.compile("第[一二三四五六七八九十百千\\d]+节");
            matcher = secondaryPattern.matcher(fullText);
            splitPoints.clear();
            while (matcher.find()) {
                splitPoints.add(matcher.start());
            }
        }

        if (splitPoints.size() < 2) {
            logger.debug("无法按章节拆分（分割点 < 2），返回原文本");
            return List.of(fullText);
        }

        List<String> chapters = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : fullText.length();
            String chapterText = fullText.substring(start, end).trim();
            if (!chapterText.isEmpty()) {
                chapters.add(chapterText);
            }
        }

        // 添加章节标题之前的前言部分（如果有）
        if (!splitPoints.isEmpty() && splitPoints.get(0) > 0) {
            String preamble = fullText.substring(0, splitPoints.get(0)).trim();
            if (!preamble.isEmpty()) {
                chapters.add(0, preamble);
            }
        }

        logger.info("按章节拆分完成: {} 个子文档", chapters.size());
        return chapters;
    }

    /**
     * 检查 Tika metadata 中是否包含 heading/章节层级信息。
     * OOXML（.docx/.pptx）和 HTML 类文档在解析时会保留这些信息。
     */
    private boolean hasHeadingMetadata(Metadata metadata) {
        // OOXML heading 层级
        for (String key : metadata.names()) {
            String lower = key.toLowerCase();
            if (lower.contains("heading") || lower.contains("outline")
                    || lower.contains("paragraph") && lower.contains("level")) {
                return true;
            }
        }
        // HTML heading
        String[] headingKeys = {"h1", "h2", "h3", "h4", "h5", "h6"};
        for (String hk : headingKeys) {
            if (metadata.get(hk) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在文本中扫描章节标题模式，返回总匹配数。
     */
    private int countChapterPatterns(String text) {
        int total = 0;
        for (Pattern pattern : CHAPTER_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                total++;
            }
        }
        return total;
    }
}
