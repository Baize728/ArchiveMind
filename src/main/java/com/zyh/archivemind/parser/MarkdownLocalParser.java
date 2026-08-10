package com.zyh.archivemind.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Order(10)
public class MarkdownLocalParser extends AbstractLocalTextParser {

    private static final Set<String> EXTENSIONS = Set.of("md", "markdown");

    @Override
    public boolean supports(ParseRequest request) {
        return hasExtension(request, EXTENSIONS);
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String markdown = readText(request.localFile());
        String title = extractTitle(markdown, request.normalizedFileName());
        return result(request, ensureTitle(markdown, title), title);
    }

    @Override
    public String parserType() {
        return "local-markdown";
    }

    @Override
    public String parserVersion() {
        return "v1";
    }
}
