package com.zyh.archivemind.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Order(20)
public class PlainTextLocalParser extends AbstractLocalTextParser {

    private static final Set<String> EXTENSIONS = Set.of("txt", "log", "text");

    @Override
    public boolean supports(ParseRequest request) {
        return hasExtension(request, EXTENSIONS);
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String text = readText(request.localFile());
        String title = extractTitle(text, request.normalizedFileName());
        return result(request, ensureTitle(text, title), title);
    }

    @Override
    public String parserType() {
        return "local-text";
    }

    @Override
    public String parserVersion() {
        return "v1";
    }
}
