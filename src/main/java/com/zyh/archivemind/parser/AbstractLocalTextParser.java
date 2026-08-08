package com.zyh.archivemind.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractLocalTextParser implements DocumentParser {

    private static final Pattern H1_PATTERN = Pattern.compile("(?m)^#\\s+(.+)$");

    protected boolean hasExtension(ParseRequest request, Set<String> extensions) {
        return extensions.contains(request.extension());
    }

    protected String readText(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            try {
                return normalize(decode(bytes, StandardCharsets.UTF_8));
            } catch (CharacterCodingException utf8Error) {
                try {
                    return normalize(decode(bytes, Charset.forName("GBK")));
                } catch (CharacterCodingException gbkError) {
                    return normalize(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取本地文本文件失败: " + path, e);
        }
    }

    protected String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
    }

    protected String ensureTitle(String markdown, String title) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        if (H1_PATTERN.matcher(markdown).find()) {
            return markdown;
        }
        return "# " + blankToDefault(title, "未命名文档") + "\n\n" + markdown;
    }

    protected String extractTitle(String markdown, String fallback) {
        if (markdown != null) {
            Matcher matcher = H1_PATTERN.matcher(markdown);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return blankToDefault(stripExtension(fallback), "未命名文档");
    }

    protected String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    protected ParseResult result(ParseRequest request, String markdown, String title) {
        String normalizedMarkdown = normalize(markdown);
        Path markdownPath = writeMarkdownTempFile(request, normalizedMarkdown);
        return resultFromFile(request, markdownPath,
                blankToDefault(title, extractTitle(normalizedMarkdown, request.normalizedFileName())));
    }

    protected ParseResult resultFromFile(ParseRequest request, Path markdownPath, String title) {
        return new ParseResult(
                markdownPath,
                fileSize(markdownPath),
                contentHash(markdownPath),
                blankToDefault(title, stripExtension(request.normalizedFileName())),
                request.language(),
                parserType(),
                parserVersion(),
                parserConfigHash(request)
        );
    }

    private Path writeMarkdownTempFile(ParseRequest request, String markdown) {
        try {
            Path source = request.localFile();
            Path dir = source != null && source.getParent() != null
                    ? source.getParent()
                    : Path.of(System.getProperty("java.io.tmpdir"));
            Files.createDirectories(dir);
            Path target = Files.createTempFile(dir, "parsed-markdown-", ".md");
            Files.writeString(target, markdown, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("写入解析 Markdown 临时文件失败: " + request.normalizedFileName(), e);
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Markdown 临时文件大小失败: " + path, e);
        }
    }

    private String contentHash(Path path) {
        try (var inputStream = Files.newInputStream(path)) {
            return org.apache.commons.codec.digest.DigestUtils.sha256Hex(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("计算 Markdown 内容 hash 失败: " + path, e);
        }
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
