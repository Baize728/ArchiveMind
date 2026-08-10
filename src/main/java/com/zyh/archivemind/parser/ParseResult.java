package com.zyh.archivemind.parser;

import java.nio.file.Path;

public record ParseResult(
        Path markdownPath,
        long markdownBytes,
        String contentHash,
        String title,
        String language,
        String parserType,
        String parserVersion,
        String parserConfigHash
) {
}
