package com.zyh.archivemind.parser;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ParserRouter {

    private final List<DocumentParser> parsers;

    public ParserRouter(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser resolve(ParseRequest request) {
        return parsers.stream()
                .filter(parser -> parser.supports(request))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "没有可用解析器: fileName=" + request.normalizedFileName()));
    }
}
