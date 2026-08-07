package com.zyh.archivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class RagasInputExporter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Path export(List<EvalResult> results, Path outputDir, int contextTopK) {
        try {
            Files.createDirectories(outputDir);
            Path output = outputDir.resolve("ragas_input.json");
            List<ObjectNode> rows = new ArrayList<>();
            for (EvalResult result : results) {
                if (result.getErrorMessage() != null) {
                    continue;
                }
                List<String> contexts = result.getRetrievedContexts() == null
                        ? List.of()
                        : result.getRetrievedContexts().stream()
                        .limit(contextTopK)
                        .map(RetrievedContext::textContent)
                        .filter(text -> text != null && !text.isBlank())
                        .toList();

                ObjectNode node = objectMapper.createObjectNode();
                node.put("case_id", result.getCaseId());
                node.put("user_input", result.getQuestion());
                node.put("response", nullToEmpty(result.getResponse()));
                node.put("reference", nullToEmpty(result.getReference()));
                node.put("question", result.getQuestion());
                node.put("answer", nullToEmpty(result.getResponse()));
                node.put("ground_truth", nullToEmpty(result.getReference()));
                node.set("retrieved_contexts", objectMapper.valueToTree(contexts));
                node.set("contexts", objectMapper.valueToTree(contexts));
                rows.add(node);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
            Files.writeString(output, json + System.lineSeparator(), StandardCharsets.UTF_8);
            return output;
        } catch (Exception e) {
            throw new RuntimeException("导出 RAGAS 输入失败", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
