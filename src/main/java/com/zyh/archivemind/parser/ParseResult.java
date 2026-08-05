package com.zyh.archivemind.parser;

public record ParseResult(
        String markdown,
        String title,
        String language,
        String parserType,
        String parserVersion,
        String parserConfigHash
) {
}
