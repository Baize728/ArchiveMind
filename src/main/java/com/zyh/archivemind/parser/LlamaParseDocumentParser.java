package com.zyh.archivemind.parser;

import com.zyh.archivemind.service.LlamaParseClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Order(1000)
public class LlamaParseDocumentParser extends AbstractLocalTextParser {

    private final LlamaParseClient llamaParseClient;
    private final String resultType;
    private final String language;
    private final String tier;
    private final String version;

    public LlamaParseDocumentParser(
            LlamaParseClient llamaParseClient,
            @Value("${llamaparse.result-type:markdown}") String resultType,
            @Value("${llamaparse.language:ch_sim}") String language,
            @Value("${llamaparse.tier:agentic}") String tier,
            @Value("${llamaparse.version:latest}") String version) {
        this.llamaParseClient = llamaParseClient;
        this.resultType = resultType;
        this.language = language;
        this.tier = tier;
        this.version = version;
    }

    @Override
    public boolean supports(ParseRequest request) {
        return true;
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        Path markdownPath = llamaParseClient.parseToFile(request.localFile(), markdownOutputDir(request));
        String title = extractTitle(readTitleSample(markdownPath), request.normalizedFileName());
        return resultFromFile(request, markdownPath, title);
    }

    @Override
    public String parserType() {
        return "llamaparse";
    }

    @Override
    public String parserVersion() {
        return "v2:" + tier + ":" + version + ":" + language + ":" + resultType;
    }

    @Override
    public String parserConfigHash(ParseRequest request) {
        return DigestUtils.sha256Hex(parserType() + ":" + parserVersion());
    }

    private Path markdownOutputDir(ParseRequest request) {
        Path localFile = request.localFile();
        if (localFile != null && localFile.getParent() != null) {
            return localFile.getParent();
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private String readTitleSample(Path markdownPath) {
        StringBuilder sample = new StringBuilder(4096);
        try (var reader = Files.newBufferedReader(markdownPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && sample.length() < 4096) {
                sample.append(line).append('\n');
            }
        } catch (Exception e) {
            return "";
        }
        return sample.toString();
    }
}
