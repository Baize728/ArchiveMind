package com.zyh.archivemind.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class EvalCaseLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<EvalCase> load(Path casesPath) {
        if (!Files.exists(casesPath)) {
            throw new IllegalArgumentException("评测 case 文件不存在: " + casesPath.toAbsolutePath());
        }

        try {
            String content = Files.readString(casesPath, StandardCharsets.UTF_8).trim();
            if (content.isBlank()) {
                return List.of();
            }
            if (content.startsWith("[")) {
                List<EvalCase> cases = objectMapper.readValue(content, new TypeReference<>() {});
                validateCases(cases);
                return cases;
            }
        } catch (Exception e) {
            throw new RuntimeException("读取 JSON 格式评测 case 失败: " + casesPath.toAbsolutePath(), e);
        }

        List<EvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(casesPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                EvalCase evalCase = objectMapper.readValue(trimmed, EvalCase.class);
                if (evalCase.question() == null || evalCase.question().isBlank()) {
                    throw new IllegalArgumentException("评测 case 缺少 question: line=" + lineNo);
                }
                cases.add(evalCase);
            }
        } catch (Exception e) {
            throw new RuntimeException("读取评测 case 失败: " + casesPath.toAbsolutePath(), e);
        }
        return cases;
    }

    private void validateCases(List<EvalCase> cases) {
        for (int i = 0; i < cases.size(); i++) {
            EvalCase evalCase = cases.get(i);
            if (evalCase.question() == null || evalCase.question().isBlank()) {
                throw new IllegalArgumentException("评测 case 缺少 question: index=" + i);
            }
        }
    }
}
