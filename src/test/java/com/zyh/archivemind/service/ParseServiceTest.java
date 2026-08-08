package com.zyh.archivemind.service;

import com.zyh.archivemind.chunk.StructureAwareChunkSplitter;
import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.parser.ParseRequest;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParseServiceTest {

    @Mock
    private DocumentVectorRepository documentVectorRepository;

    @Mock
    private ContextGenerator contextGenerator;

    @Mock
    private ParsedDocumentService parsedDocumentService;

    @TempDir
    private Path tempDir;

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(parseService, "contextGenerator", contextGenerator);
        ReflectionTestUtils.setField(parseService, "parsedDocumentService", parsedDocumentService);
        ReflectionTestUtils.setField(parseService, "structureDetector", new DocumentStructureDetector());
        ReflectionTestUtils.setField(parseService, "chunkSplitter", new StructureAwareChunkSplitter());
        ReflectionTestUtils.setField(parseService, "chunkSize", 1_000);
        ReflectionTestUtils.setField(parseService, "chunkOverlapSize", 0);
        ReflectionTestUtils.setField(parseService, "bufferSize", 1_024);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 1.0);
        ReflectionTestUtils.setField(parseService, "tempDir", tempDir.toString());
        ReflectionTestUtils.setField(parseService, "cchFallbackMaxChunks", 200);
        ReflectionTestUtils.setField(parseService, "maxStructuredUnitLength", 1_000);
        ReflectionTestUtils.setField(parseService, "parseLanguage", "ch_sim");
        ReflectionTestUtils.setField(parseService, "inMemoryMarkdownMaxBytes", 5 * 1024 * 1024L);
    }

    @Test
    void shouldGenerateIndependentBriefsForStructuredDocumentBelowCchThreshold() throws Exception {
        String body1 = "第一章正文" + "甲".repeat(220);
        String body2 = "第二章正文" + "乙".repeat(220);
        String body3 = "第三章正文" + "丙".repeat(220);
        String markdown = "# 第一章\n\n" + body1
                + "\n\n# 第二章\n\n" + body2
                + "\n\n# 第三章\n\n" + body3;

        when(parsedDocumentService.getOrParseRef(any(ParseRequest.class))).thenReturn(parsedMarkdownRef(markdown));
        when(contextGenerator.generateDocumentBrief(anyString())).thenReturn("文档单元画像");
        when(contextGenerator.generateContext(anyString(), anyString(), anyString())).thenReturn("LLM chunk 上下文");

        parseService.parseAndSave(
                "file-md5",
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)),
                "manual.md",
                "user-1",
                "DEFAULT",
                false);

        ArgumentCaptor<String> briefCaptor = ArgumentCaptor.forClass(String.class);
        verify(contextGenerator, times(3)).generateDocumentBrief(briefCaptor.capture());
        assertThat(briefCaptor.getAllValues())
                .hasSize(3)
                .satisfiesExactly(
                        unit -> assertThat(unit).contains("# 第一章").doesNotContain("# 第二章"),
                        unit -> assertThat(unit).contains("# 第二章").doesNotContain("# 第三章"),
                        unit -> assertThat(unit).contains("# 第三章"));

        List<DocumentVector> saved = savedVectors();
        assertThat(saved).extracting(DocumentVector::getChunkId).containsExactly(1, 2, 3);
        assertThat(saved).extracting(DocumentVector::getStartOffset)
                .containsExactly(markdown.indexOf(body1), markdown.indexOf(body2), markdown.indexOf(body3));
        assertThat(saved).extracting(DocumentVector::getDocTitle)
                .containsExactly("第一章", "第二章", "第三章");
    }

    @Test
    void shouldSplitLargeStructuredUnitByLowerLevelHeadings() throws Exception {
        String background = "背景内容" + "甲".repeat(220);
        String implementation = "实施内容" + "乙".repeat(220);
        String markdown = "# 第一章\n\n"
                + "## 背景\n\n" + background
                + "\n\n## 实施\n\n" + implementation
                + "\n\n# 第二章\n\n第二章内容"
                + "\n\n# 第三章\n\n第三章内容";

        ReflectionTestUtils.setField(parseService, "maxStructuredUnitLength", 100);
        when(parsedDocumentService.getOrParseRef(any(ParseRequest.class))).thenReturn(parsedMarkdownRef(markdown));
        when(contextGenerator.generateDocumentBrief(anyString())).thenReturn("文档单元画像");
        when(contextGenerator.generateContext(anyString(), anyString(), anyString())).thenReturn("LLM chunk 上下文");

        parseService.parseAndSave(
                "file-md5",
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)),
                "manual.md",
                "user-1",
                "DEFAULT",
                false);

        ArgumentCaptor<String> briefCaptor = ArgumentCaptor.forClass(String.class);
        verify(contextGenerator, times(4)).generateDocumentBrief(briefCaptor.capture());
        assertThat(briefCaptor.getAllValues())
                .anySatisfy(unit -> assertThat(unit).contains("# 第一章").contains("## 背景"))
                .anySatisfy(unit -> assertThat(unit).contains("# 第一章").contains("## 实施"));

        List<DocumentVector> saved = savedVectors();
        DocumentVector backgroundVector = saved.stream()
                .filter(vector -> vector.getTextContent().contains(background))
                .findFirst()
                .orElseThrow();
        DocumentVector implementationVector = saved.stream()
                .filter(vector -> vector.getTextContent().contains(implementation))
                .findFirst()
                .orElseThrow();

        assertThat(backgroundVector.getDocTitle()).isEqualTo("第一章");
        assertThat(backgroundVector.getHeadingPath()).isEqualTo("第一章 > 背景");
        assertThat(backgroundVector.getStartOffset()).isEqualTo(markdown.indexOf(background));
        assertThat(implementationVector.getDocTitle()).isEqualTo("第一章");
        assertThat(implementationVector.getHeadingPath()).isEqualTo("第一章 > 实施");
        assertThat(implementationVector.getStartOffset()).isEqualTo(markdown.indexOf(implementation));
    }

    @Test
    void shouldSplitNumberedStructuredDocumentBelowCchThreshold() throws Exception {
        String markdown = """
                1. 概述
                概述正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文。

                2. 实施
                实施正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文。

                3. 验证
                验证正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文正文。
                """;

        when(parsedDocumentService.getOrParseRef(any(ParseRequest.class))).thenReturn(parsedMarkdownRef(markdown));
        when(contextGenerator.generateDocumentBrief(anyString())).thenReturn("文档单元画像");

        parseService.parseAndSave(
                "file-md5",
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)),
                "manual.txt",
                "user-1",
                "DEFAULT",
                false);

        ArgumentCaptor<String> briefCaptor = ArgumentCaptor.forClass(String.class);
        verify(contextGenerator, times(3)).generateDocumentBrief(briefCaptor.capture());
        assertThat(briefCaptor.getAllValues())
                .satisfiesExactly(
                        unit -> assertThat(unit).contains("1. 概述").doesNotContain("2. 实施"),
                        unit -> assertThat(unit).contains("2. 实施").doesNotContain("3. 验证"),
                        unit -> assertThat(unit).contains("3. 验证"));

        List<DocumentVector> saved = savedVectors();
        assertThat(saved).hasSize(3);
    }

    @Test
    void shouldUseCchPrefixAndSkipChunkLlmForUnstructuredDocumentAboveChunkThreshold() throws Exception {
        String markdown = "这是一段没有显式章节结构的长文档。" + "内容".repeat(260);

        ReflectionTestUtils.setField(parseService, "chunkSize", 300);
        ReflectionTestUtils.setField(parseService, "cchFallbackMaxChunks", 2);
        when(parsedDocumentService.getOrParseRef(any(ParseRequest.class))).thenReturn(parsedMarkdownRef(markdown));
        when(contextGenerator.generateDocumentBrief(anyString())).thenReturn("全文画像");

        parseService.parseAndSave(
                "file-md5",
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)),
                "plain.txt",
                "user-1",
                "DEFAULT",
                false);

        verify(contextGenerator, times(1)).generateDocumentBrief(markdown);
        verify(contextGenerator, never()).generateContext(anyString(), anyString(), anyString());

        List<DocumentVector> saved = savedVectors();
        assertThat(saved).isNotEmpty();
        assertThat(saved)
                .allSatisfy(vector -> assertThat(vector.getContextualizedContent())
                        .contains("文档无显式章节结构，以下片段来自同一份超长文档。"));
    }

    @Test
    void shouldUseChunkLlmForUnstructuredDocumentBelowChunkThreshold() throws Exception {
        String markdown = "这是一段没有显式章节结构的普通文档。" + "内容".repeat(120);

        when(parsedDocumentService.getOrParseRef(any(ParseRequest.class))).thenReturn(parsedMarkdownRef(markdown));
        when(contextGenerator.generateDocumentBrief(anyString())).thenReturn("全文画像");
        when(contextGenerator.generateContext(anyString(), anyString(), anyString())).thenReturn("LLM chunk 上下文");

        parseService.parseAndSave(
                "file-md5",
                new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)),
                "plain.txt",
                "user-1",
                "DEFAULT",
                false);

        verify(contextGenerator, times(1)).generateDocumentBrief(markdown);
        verify(contextGenerator, times(1)).generateContext(anyString(), anyString(), anyString());

        List<DocumentVector> saved = savedVectors();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getContextualizedContent()).startsWith("LLM chunk 上下文");
    }

    @SuppressWarnings("unchecked")
    private List<DocumentVector> savedVectors() {
        ArgumentCaptor<Iterable<DocumentVector>> saveCaptor =
                (ArgumentCaptor<Iterable<DocumentVector>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Iterable.class);
        verify(documentVectorRepository, times(1)).deleteByFileMd5AndUserId("file-md5", "user-1");
        verify(documentVectorRepository, atLeastOnce()).saveAll(saveCaptor.capture());

        List<DocumentVector> saved = new ArrayList<>();
        for (Iterable<DocumentVector> batch : saveCaptor.getAllValues()) {
            StreamSupport.stream(batch.spliterator(), false).forEach(saved::add);
        }
        return saved;
    }

    private ParsedMarkdownRef parsedMarkdownRef(String markdown) throws Exception {
        Path markdownPath = Files.createTempFile(tempDir, "parsed-test-", ".md");
        Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);
        return new ParsedMarkdownRef(
                markdownPath,
                "parsed-documents/test.md",
                Files.size(markdownPath),
                "test-hash",
                false);
    }
}
