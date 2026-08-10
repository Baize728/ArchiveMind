package com.zyh.archivemind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构检测器。
 * 通过 Markdown 标题（LlamaParse 输出）或正则扫描判断文档是否有章节结构，
 * 并支持按 Markdown 标题层级拆分文档单元。
 */
@Service
public class DocumentStructureDetector {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStructureDetector.class);

    /** 视为"有结构"的最低章节标题匹配数 */
    private static final int MIN_CHAPTER_COUNT = 3;

    private static final Pattern MARKDOWN_HEADING_PATTERN =
            Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");

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

        // 兼容非 Markdown 的编号标题结构
        if (splitPoints.size() < 2) {
            splitPoints = collectSplitPoints(fullText, Pattern.compile("(?m)^\\d+\\.(?!\\d)\\s*\\S.*$"));
        }

        if (splitPoints.size() < 2) {
            splitPoints = collectSplitPoints(fullText, Pattern.compile("(?m)^\\d+\\.\\d+(?!\\.\\d)\\s*\\S.*$"));
        }

        if (splitPoints.size() < 2) {
            splitPoints = collectSplitPoints(fullText, Pattern.compile("(?m)^[一二三四五六七八九十]+、\\s*\\S.*$"));
        }

        if (splitPoints.size() < 2) {
            splitPoints = collectSplitPoints(fullText, Pattern.compile("(?im)^Chapter\\s+\\d+.*$"));
        }

        if (splitPoints.size() < 2) {
            splitPoints = collectSplitPoints(fullText, Pattern.compile("(?im)^Section\\s+\\d+.*$"));
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

    private List<Integer> collectSplitPoints(String fullText, Pattern pattern) {
        Matcher matcher = pattern.matcher(fullText);
        List<Integer> splitPoints = new ArrayList<>();
        while (matcher.find()) {
            splitPoints.add(matcher.start());
        }
        return splitPoints;
    }

    /**
     * 按 Markdown 标题结构拆分文档单元。先选择最浅且存在多个标题的层级作为首层单元；
     * 当单元超过画像输入上限时，再优先向更低级标题递归拆分。
     *
     * @param fullText 完整 Markdown 文本
     * @param maxUnitLength 单个文档单元的画像输入上限
     * @return 带原文偏移和补充标题前缀的文档单元；无 Markdown 标题结构时返回空列表
     */
    public List<StructuredDocumentUnit> splitStructuredDocument(String fullText, int maxUnitLength) {
        List<HeadingNode> headingNodes = parseMarkdownHeadingTree(fullText);
        if (headingNodes.isEmpty()) {
            return List.of();
        }

        List<HeadingNode> initialNodes = selectInitialUnitNodes(headingNodes);
        if (initialNodes.isEmpty()) {
            return List.of();
        }

        int effectiveMaxUnitLength = Math.max(1, maxUnitLength);
        List<StructuredDocumentUnit> units = new ArrayList<>();

        HeadingNode firstNode = initialNodes.get(0);
        addRangeAsUnit(fullText, 0, firstNode.start, List.of(), units);

        for (HeadingNode node : initialNodes) {
            appendNodeUnits(fullText, node, ancestorNodes(node), effectiveMaxUnitLength, units);
        }

        logger.info("Markdown 结构单元拆分完成: {} 个单元, 首层级标题数: {}, 单元画像上限: {}",
                units.size(), initialNodes.size(), effectiveMaxUnitLength);
        return units;
    }

    private List<HeadingNode> parseMarkdownHeadingTree(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }

        List<HeadingNode> allNodes = new ArrayList<>();
        Deque<HeadingNode> stack = new ArrayDeque<>();
        Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(fullText);

        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).replaceAll("\\s+#*$", "").trim();
            HeadingNode node = new HeadingNode(level, title, matcher.start());

            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop().end = matcher.start();
            }

            if (!stack.isEmpty()) {
                node.parent = stack.peek();
                stack.peek().children.add(node);
            }

            stack.push(node);
            allNodes.add(node);
        }

        while (!stack.isEmpty()) {
            stack.pop().end = fullText.length();
        }
        return allNodes;
    }

    private List<HeadingNode> selectInitialUnitNodes(List<HeadingNode> allNodes) {
        for (int level = 1; level <= 6; level++) {
            List<HeadingNode> nodesAtLevel = new ArrayList<>();
            for (HeadingNode node : allNodes) {
                if (node.level == level) {
                    nodesAtLevel.add(node);
                }
            }
            if (nodesAtLevel.size() >= 2) {
                return nodesAtLevel;
            }
        }
        return List.of();
    }

    private void appendNodeUnits(String fullText, HeadingNode node, List<HeadingNode> ancestors,
                                 int maxUnitLength, List<StructuredDocumentUnit> units) {
        if (node.length() <= maxUnitLength || !hasSplittableDescendant(node)) {
            addRangeAsUnit(fullText, node.start, node.end, ancestors, units);
            return;
        }

        HeadingNode firstChild = node.children.get(0);
        addRangeAsUnit(fullText, node.start, firstChild.start, ancestors, units);

        List<HeadingNode> childAncestors = new ArrayList<>(ancestors);
        childAncestors.add(node);
        for (HeadingNode child : node.children) {
            appendNodeUnits(fullText, child, childAncestors, maxUnitLength, units);
        }
    }

    private boolean hasSplittableDescendant(HeadingNode node) {
        if (node.children.size() >= 2) {
            return true;
        }
        for (HeadingNode child : node.children) {
            if (hasSplittableDescendant(child)) {
                return true;
            }
        }
        return false;
    }

    private List<HeadingNode> ancestorNodes(HeadingNode node) {
        List<HeadingNode> ancestors = new ArrayList<>();
        HeadingNode current = node.parent;
        while (current != null) {
            ancestors.add(0, current);
            current = current.parent;
        }
        return ancestors;
    }

    private void addRangeAsUnit(String fullText, int start, int end, List<HeadingNode> ancestors,
                                List<StructuredDocumentUnit> units) {
        int trimmedStart = start;
        while (trimmedStart < end && Character.isWhitespace(fullText.charAt(trimmedStart))) {
            trimmedStart++;
        }

        int trimmedEnd = end;
        while (trimmedEnd > trimmedStart && Character.isWhitespace(fullText.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }

        if (trimmedStart >= trimmedEnd) {
            return;
        }

        String sourceText = fullText.substring(trimmedStart, trimmedEnd);
        if (!containsContentBeyondHeadings(sourceText)) {
            return;
        }

        String headingPrefix = buildHeadingPrefix(ancestors);
        units.add(new StructuredDocumentUnit(
                headingPrefix + sourceText,
                trimmedStart,
                headingPrefix.length()));
    }

    private boolean containsContentBeyondHeadings(String text) {
        return !MARKDOWN_HEADING_PATTERN.matcher(text).replaceAll("").trim().isEmpty();
    }

    private String buildHeadingPrefix(List<HeadingNode> ancestors) {
        if (ancestors.isEmpty()) {
            return "";
        }

        StringBuilder prefix = new StringBuilder();
        for (HeadingNode heading : ancestors) {
            prefix.append("#".repeat(heading.level))
                    .append(" ")
                    .append(heading.title)
                    .append("\n\n");
        }
        return prefix.toString();
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

    public record StructuredDocumentUnit(String text, int sourceStartOffset, int syntheticPrefixLength) {
    }

    private static class HeadingNode {
        private final int level;
        private final String title;
        private final int start;
        private int end;
        private HeadingNode parent;
        private final List<HeadingNode> children = new ArrayList<>();

        private HeadingNode(int level, String title, int start) {
            this.level = level;
            this.title = title;
            this.start = start;
        }

        private int length() {
            return end - start;
        }
    }
}
