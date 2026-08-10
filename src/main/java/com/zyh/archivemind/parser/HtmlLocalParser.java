package com.zyh.archivemind.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Order(30)
public class HtmlLocalParser extends AbstractLocalTextParser {

    private static final Set<String> EXTENSIONS = Set.of("html", "htm");

    @Override
    public boolean supports(ParseRequest request) {
        return hasExtension(request, EXTENSIONS);
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String html = readText(request.localFile());
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript,nav,footer").remove();

        String title = document.title();
        if (title == null || title.isBlank()) {
            title = stripExtension(request.normalizedFileName());
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");

        for (Element element : document.body().select("h1,h2,h3,h4,h5,h6,p,li")) {
            String text = element.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            String tag = element.tagName().toLowerCase();
            if (tag.matches("h[1-6]")) {
                int level = Math.max(1, Integer.parseInt(tag.substring(1)));
                markdown.append("#".repeat(level)).append(" ").append(text).append("\n\n");
            } else if ("li".equals(tag)) {
                markdown.append("- ").append(text).append("\n");
            } else {
                markdown.append(text).append("\n\n");
            }
        }

        String parsed = markdown.toString().trim();
        if (parsed.isBlank()) {
            parsed = "# " + title + "\n\n" + document.text();
        }
        return result(request, parsed, title);
    }

    @Override
    public String parserType() {
        return "local-html";
    }

    @Override
    public String parserVersion() {
        return "v1";
    }
}
