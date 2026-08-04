package com.zyh.archivemind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构检测器。
 * 通过 Markdown 标题（LlamaParse 输出）或正则扫描判断文档是否有章节结构，
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
        // N. 单级编号标题（行首，如 "1. 概述"）
        Pattern.compile("^\\d+\\.(?!\\d)", Pattern.MULTILINE),
        // N.N 二级编号标题（如 "1.1 背景"）
        Pattern.compile("^\\d+\\.\\d+", Pattern.MULTILINE),
        // N.N.N 三级编号标题（如 "1.1.1 细节"）
        Pattern.compile("^\\d+\\.\\d+\\.\\d+", Pattern.MULTILINE),
        // 一、二、三、 中文顿号编号
        Pattern.compile("^[一二三四五六七八九十]+、", Pattern.MULTILINE),
        // Chapter N / Section N
        Pattern.compile("Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Section\\s+\\d+", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 综合判断文档是否有结构。
     * 优先检测 Markdown 标题（LlamaParse 输出），其次正则扫描章节标题。
     *
     * @param fullText 提取出的完整文本（LlamaParse Markdown 输出）
     * @return true 表示有章节结构，false 表示无结构
     */
    public boolean hasStructure(String fullText) {
        // 路径1：Markdown 标题（LlamaParse 自动生成，# ## ### 等）
        int mdHeadingCount = countMarkdownHeadings(fullText);
        if (mdHeadingCount >= MIN_CHAPTER_COUNT) {
            logger.debug("通过 Markdown 标题判断为有结构文档（{} 个标题）", mdHeadingCount);
            return true;
        }

        // 路径2：正则扫描章节标题
        int matchCount = countChapterPatterns(fullText);
        boolean hasStructure = matchCount >= MIN_CHAPTER_COUNT;
        logger.debug("正则扫描章节标题匹配数: {}, Markdown标题数: {}, 判定有结构: {}",
                matchCount, mdHeadingCount, hasStructure);
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
        // 优先按一级 Markdown 标题（# xxx）拆分
        Pattern mdH1 = Pattern.compile("(?m)^#[^#].*");
        Matcher m1 = mdH1.matcher(fullText);
        List<Integer> splitPoints = new ArrayList<>();
        while (m1.find()) {
            splitPoints.add(m1.start());
        }

        // 如果一级标题不够，尝试二级标题
        if (splitPoints.size() < 2) {
            Pattern mdH2 = Pattern.compile("(?m)^##[^#].*");
            Matcher m2 = mdH2.matcher(fullText);
            splitPoints.clear();
            while (m2.find()) {
                splitPoints.add(m2.start());
            }
        }

        // 如果 Markdown 标题不够，尝试 "第N章" 模式
        if (splitPoints.size() < 2) {
            Pattern primaryPattern = Pattern.compile("第[一二三四五六七八九十百千\\d]+章");
            Matcher matcher = primaryPattern.matcher(fullText);
            splitPoints.clear();
            while (matcher.find()) {
                splitPoints.add(matcher.start());
            }
        }

        // 如果还不够，尝试 "第N节"
        if (splitPoints.size() < 2) {
            Pattern secondaryPattern = Pattern.compile("第[一二三四五六七八九十百千\\d]+节");
            Matcher matcher = secondaryPattern.matcher(fullText);
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
     * 在 Markdown 文本中统计标题行数。
     */
    private int countMarkdownHeadings(String text) {
        int count = 0;
        Pattern headingPattern = Pattern.compile("(?m)^#{1,6}\\s+");
        Matcher matcher = headingPattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
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
