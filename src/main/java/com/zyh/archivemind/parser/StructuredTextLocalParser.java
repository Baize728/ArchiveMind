package com.zyh.archivemind.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Order(40)
public class StructuredTextLocalParser extends AbstractLocalTextParser {

    private static final Set<String> EXTENSIONS = Set.of("json", "csv");

    @Override
    public boolean supports(ParseRequest request) {
        return hasExtension(request, EXTENSIONS);
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String text = readText(request.localFile());
        String extension = request.extension();
        String title = stripExtension(request.normalizedFileName());
        String markdown = "# " + title + "\n\n```" + extension + "\n" + text + "\n```";
        return result(request, markdown, title);
    }

    @Override
    public String parserType() {
        return "local-structured-text";
    }

    @Override
    public String parserVersion() {
        return "v1";
    }
}
