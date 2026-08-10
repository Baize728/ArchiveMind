package com.zyh.archivemind.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构感知切割器。
 * 保守识别标题、代码块、表格、列表和普通段落，优先保证 chunk 不跨章节、不破坏结构块。
 */
@Service
public class StructureAwareChunkSplitter {

    private static final Logger logger = LoggerFactory.getLogger(StructureAwareChunkSplitter.class);
    private static final String DEFAULT_TITLE = "未命名文档";
    private static final int MIN_CHUNK_SIZE = 200;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_PATTERN = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？；])|(?<=[.!?;])\\s+");

    public List<ChunkUnit> split(String markdown, int chunkSize, int overlapSize) {
        String normalized = normalize(markdown);
        if (normalized.isBlank()) {
            return List.of();
        }

        int effectiveChunkSize = Math.max(chunkSize, MIN_CHUNK_SIZE);
        int effectiveOverlap = Math.max(0, Math.min(overlapSize, effectiveChunkSize / 3));

        ParsedBlocks parsedBlocks = parseBlocks(normalized);
        if (parsedBlocks.blocks().isEmpty()) {
            return List.of(new ChunkUnit(
                    normalized,
                    parsedBlocks.docTitle(),
                    parsedBlocks.docTitle(),
                    "paragraph",
                    0,
                    normalized.length(),
                    estimateTokenLength(normalized)
            ));
        }

        List<Block> expandedBlocks = new ArrayList<>();
        for (Block block : parsedBlocks.blocks()) {
            expandedBlocks.addAll(splitOversizedBlock(block, effectiveChunkSize, effectiveOverlap));
        }

        List<ChunkUnit> chunks = mergeBlocks(expandedBlocks, parsedBlocks.docTitle(), effectiveChunkSize);
        logger.debug("结构感知切割完成: blocks={}, chunks={}, chunkSize={}, overlap={}",
                parsedBlocks.blocks().size(), chunks.size(), effectiveChunkSize, effectiveOverlap);
        return chunks;
    }

    private ParsedBlocks parseBlocks(String markdown) {
        List<Line> lines = toLines(markdown);
        List<Block> blocks = new ArrayList<>();
        String[] headings = new String[6];
        String docTitle = DEFAULT_TITLE;
        boolean docTitleResolved = false;

        int i = 0;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String trimmed = line.text().trim();

            if (trimmed.isBlank()) {
                i++;
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length();
                String title = cleanupHeading(headingMatcher.group(2));
                headings[level - 1] = title;
                for (int j = level; j < headings.length; j++) {
                    headings[j] = null;
                }
                if (level == 1 && !docTitleResolved) {
                    docTitle = title;
                    docTitleResolved = true;
                }
                i++;
                continue;
            }

            String headingPath = buildHeadingPath(headings, docTitle);
            if (isFenceStart(trimmed)) {
                CollectResult collected = collectCodeBlock(lines, i);
                blocks.add(toBlock(collected.lines(), "code", headingPath));
                i = collected.nextIndex();
                continue;
            }

            if (isTableLine(trimmed)) {
                CollectResult collected = collectByType(lines, i, this::isTableLine);
                blocks.add(toBlock(collected.lines(), "table", headingPath));
                i = collected.nextIndex();
                continue;
            }

            if (isListLine(line.text())) {
                CollectResult collected = collectListBlock(lines, i);
                blocks.add(toBlock(collected.lines(), "list", headingPath));
                i = collected.nextIndex();
                continue;
            }

            CollectResult collected = collectParagraphBlock(lines, i);
            blocks.add(toBlock(collected.lines(), "paragraph", headingPath));
            i = collected.nextIndex();
        }

        if (!docTitleResolved) {
            docTitle = inferTitle(markdown);
        }

        return new ParsedBlocks(blocks, docTitle);
    }

    private List<Block> splitOversizedBlock(Block block, int chunkSize, int overlapSize) {
        if (block.content().length() <= chunkSize) {
            return List.of(block);
        }

        if ("code".equals(block.blockType()) || "table".equals(block.blockType())
                || "list".equals(block.blockType())) {
            return splitByLines(block, chunkSize);
        }

        List<String> units = Arrays.stream(SENTENCE_BOUNDARY.split(block.content()))
                .filter(s -> !s.isBlank())
                .toList();
        if (units.isEmpty()) {
            return splitByCharacters(block, chunkSize, overlapSize);
        }

        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            String sentence = unit.trim();
            if (sentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    segments.add(current.toString().trim());
                    current = new StringBuilder();
                }
                splitTextByCharacters(sentence, chunkSize, overlapSize).forEach(segments::add);
                continue;
            }

            if (current.length() + sentence.length() > chunkSize && !current.isEmpty()) {
                segments.add(current.toString().trim());
                current = new StringBuilder(overlapTail(segments.get(segments.size() - 1), overlapSize));
                if (!current.isEmpty()) {
                    current.append("\n");
                }
            }
            current.append(sentence);
        }

        if (!current.isEmpty()) {
            segments.add(current.toString().trim());
        }

        return toSegmentBlocks(block, segments);
    }

    private List<Block> splitByLines(Block block, int chunkSize) {
        List<Block> result = new ArrayList<>();
        String[] lines = block.content().split("(?<=\\n)");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.length() > chunkSize) {
                if (!current.isEmpty()) {
                    result.add(segmentBlock(block, current.toString().trim()));
                    current = new StringBuilder();
                }
                splitTextByCharacters(line, chunkSize, 0)
                        .forEach(segment -> result.add(segmentBlock(block, segment)));
                continue;
            }

            if (current.length() + line.length() > chunkSize && !current.isEmpty()) {
                result.add(segmentBlock(block, current.toString().trim()));
                current = new StringBuilder();
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            result.add(segmentBlock(block, current.toString().trim()));
        }
        return result;
    }

    private List<Block> splitByCharacters(Block block, int chunkSize, int overlapSize) {
        return splitTextByCharacters(block.content(), chunkSize, overlapSize).stream()
                .map(segment -> segmentBlock(block, segment))
                .toList();
    }

    private List<String> splitTextByCharacters(String text, int chunkSize, int overlapSize) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String segment = text.substring(start, end).trim();
            if (!segment.isBlank()) {
                segments.add(segment);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlapSize, start + 1);
        }
        return segments;
    }

    private List<Block> toSegmentBlocks(Block source, List<String> segments) {
        return segments.stream()
                .filter(segment -> !segment.isBlank())
                .map(segment -> segmentBlock(source, segment))
                .toList();
    }

    private Block segmentBlock(Block source, String segment) {
        int localStart = source.content().indexOf(segment);
        if (localStart < 0) {
            localStart = 0;
        }
        int start = Math.min(source.endOffset(), source.startOffset() + localStart);
        int end = Math.min(source.endOffset(), start + segment.length());
        return new Block(segment, source.blockType(), source.headingPath(), start, end);
    }

    private List<ChunkUnit> mergeBlocks(List<Block> blocks, String docTitle, int chunkSize) {
        List<ChunkUnit> chunks = new ArrayList<>();
        ChunkBuilder current = new ChunkBuilder(docTitle);

        for (Block block : blocks) {
            Block effectiveBlock = normalizeBlockHeading(block, docTitle);
            if (!current.isEmpty() && !current.canAccept(effectiveBlock, chunkSize)) {
                chunks.add(current.toChunk());
                current = new ChunkBuilder(docTitle);
            }
            current.add(effectiveBlock);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toChunk());
        }
        return chunks;
    }

    private Block normalizeBlockHeading(Block block, String docTitle) {
        String headingPath = block.headingPath();
        if (headingPath == null || headingPath.isBlank() || DEFAULT_TITLE.equals(headingPath)) {
            headingPath = docTitle;
        }
        return new Block(block.content(), block.blockType(), headingPath, block.startOffset(), block.endOffset());
    }

    private CollectResult collectCodeBlock(List<Line> lines, int startIndex) {
        String fence = lines.get(startIndex).text().trim().startsWith("~~~") ? "~~~" : "```";
        List<Line> collected = new ArrayList<>();
        collected.add(lines.get(startIndex));
        int i = startIndex + 1;
        while (i < lines.size()) {
            Line line = lines.get(i);
            collected.add(line);
            if (line.text().trim().startsWith(fence)) {
                i++;
                break;
            }
            i++;
        }
        return new CollectResult(collected, i);
    }

    private CollectResult collectByType(List<Line> lines, int startIndex, LineTypeMatcher matcher) {
        List<Line> collected = new ArrayList<>();
        int i = startIndex;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String trimmed = line.text().trim();
            if (trimmed.isBlank() || isHeadingLine(trimmed) || !matcher.matches(trimmed)) {
                break;
            }
            collected.add(line);
            i++;
        }
        return new CollectResult(collected, i);
    }

    private CollectResult collectListBlock(List<Line> lines, int startIndex) {
        List<Line> collected = new ArrayList<>();
        int i = startIndex;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String trimmed = line.text().trim();
            if (trimmed.isBlank() || isHeadingLine(trimmed) || isFenceStart(trimmed) || isTableLine(trimmed)) {
                break;
            }
            if (!isListLine(line.text()) && !line.text().startsWith(" ") && !line.text().startsWith("\t")) {
                break;
            }
            collected.add(line);
            i++;
        }
        return new CollectResult(collected, i);
    }

    private CollectResult collectParagraphBlock(List<Line> lines, int startIndex) {
        List<Line> collected = new ArrayList<>();
        int i = startIndex;
        while (i < lines.size()) {
            Line line = lines.get(i);
            String trimmed = line.text().trim();
            if (trimmed.isBlank() || isHeadingLine(trimmed) || isFenceStart(trimmed)
                    || isTableLine(trimmed) || isListLine(line.text())) {
                break;
            }
            collected.add(line);
            i++;
        }
        return new CollectResult(collected, i);
    }

    private Block toBlock(List<Line> lines, String blockType, String headingPath) {
        if (lines.isEmpty()) {
            return new Block("", blockType, headingPath, 0, 0);
        }
        int start = lines.get(0).start();
        int end = lines.get(lines.size() - 1).end();
        StringBuilder content = new StringBuilder();
        for (Line line : lines) {
            if (!content.isEmpty()) {
                content.append("\n");
            }
            content.append(line.text());
        }
        return new Block(content.toString().trim(), blockType, headingPath, start, end);
    }

    private List<Line> toLines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline >= 0 ? newline + 1 : text.length();
            String raw = text.substring(start, end);
            String lineText = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
            lines.add(new Line(lineText, start, end));
            start = end;
        }
        return lines;
    }

    private boolean isHeadingLine(String trimmed) {
        return HEADING_PATTERN.matcher(trimmed).matches();
    }

    private boolean isFenceStart(String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private boolean isTableLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.indexOf('|') < trimmed.lastIndexOf('|');
    }

    private boolean isListLine(String line) {
        return LIST_PATTERN.matcher(line).matches();
    }

    private String buildHeadingPath(String[] headings, String docTitle) {
        List<String> path = new ArrayList<>();
        for (String heading : headings) {
            if (heading != null && !heading.isBlank()) {
                path.add(heading);
            }
        }
        if (path.isEmpty()) {
            return docTitle;
        }
        return String.join(" > ", path);
    }

    private String cleanupHeading(String heading) {
        return heading == null ? DEFAULT_TITLE : heading.replaceAll("\\s+#*$", "").trim();
    }

    private String inferTitle(String markdown) {
        return Arrays.stream(markdown.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .map(line -> line.length() > 80 ? line.substring(0, 80) : line)
                .orElse(DEFAULT_TITLE);
    }

    private String overlapTail(String text, int overlapSize) {
        if (overlapSize <= 0 || text.length() <= overlapSize) {
            return "";
        }
        return text.substring(text.length() - overlapSize).trim();
    }

    private String normalize(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown.replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
    }

    private int estimateTokenLength(String text) {
        return text == null ? 0 : text.length();
    }

    @FunctionalInterface
    private interface LineTypeMatcher {
        boolean matches(String trimmed);
    }

    private record Line(String text, int start, int end) {
    }

    private record Block(String content, String blockType, String headingPath, int startOffset, int endOffset) {
    }

    private record ParsedBlocks(List<Block> blocks, String docTitle) {
    }

    private record CollectResult(List<Line> lines, int nextIndex) {
    }

    private static class ChunkBuilder {
        private final String docTitle;
        private final StringBuilder content = new StringBuilder();
        private String headingPath;
        private String blockType;
        private int startOffset = -1;
        private int endOffset = -1;

        private ChunkBuilder(String docTitle) {
            this.docTitle = docTitle;
        }

        private boolean isEmpty() {
            return content.isEmpty();
        }

        private boolean canAccept(Block block, int chunkSize) {
            if (isEmpty()) {
                return true;
            }
            if (!Objects.equals(headingPath, block.headingPath())) {
                return false;
            }
            int separatorLength = 2;
            return content.length() + separatorLength + block.content().length() <= chunkSize;
        }

        private void add(Block block) {
            if (block.content().isBlank()) {
                return;
            }
            if (content.isEmpty()) {
                headingPath = block.headingPath();
                blockType = block.blockType();
                startOffset = block.startOffset();
            } else {
                content.append("\n\n");
                if (!Objects.equals(blockType, block.blockType())) {
                    blockType = "mixed";
                }
            }
            content.append(block.content());
            endOffset = block.endOffset();
        }

        private ChunkUnit toChunk() {
            String chunkContent = content.toString().trim();
            return new ChunkUnit(
                    chunkContent,
                    docTitle,
                    headingPath,
                    blockType,
                    Math.max(startOffset, 0),
                    Math.max(endOffset, Math.max(startOffset, 0)),
                    chunkContent.length()
            );
        }
    }
}
