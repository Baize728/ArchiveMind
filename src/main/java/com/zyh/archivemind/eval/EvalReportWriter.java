package com.zyh.archivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

@Component
public class EvalReportWriter {

    private static final DecimalFormat DF = new DecimalFormat("0.0000");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(List<EvalResult> results, Path outputDir, EvalProperties properties, Path ragasInputPath) {
        try {
            Files.createDirectories(outputDir);
            writeJson(results, outputDir.resolve("eval_result.json"));
            writeCsv(results, outputDir.resolve("eval_report.csv"));
            writeMarkdown(results, outputDir.resolve("eval_report.md"), properties, ragasInputPath);
        } catch (Exception e) {
            throw new RuntimeException("写出评测报告失败", e);
        }
    }

    private void writeJson(List<EvalResult> results, Path path) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private void writeCsv(List<EvalResult> results, Path path) throws Exception {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",",
                    "runId", "caseId", "caseType", "expectedAnswerable",
                    "hitAt1", "hitAt3", "hitAt5", "recallAt5", "recallAt10",
                    "precisionAt5", "precisionAt10", "mrr", "sourceHitAt5",
                    "permissionLeak", "dbEsConsistent", "contextualizedRate",
                    "noAnswerFalsePositive",
                    "retrievalLatencyMs", "answerGenerated", "mustContainPass",
                    "mustNotContainPass", "sourceReferencePass", "refusalPass",
                    "errorMessage"));
            writer.newLine();
            for (EvalResult result : results) {
                RetrievalMetricResult r = result.getRetrieval();
                IngestionCheckResult i = result.getIngestion();
                AnswerCheckResult a = result.getAnswerCheck();
                List<String> cells = new ArrayList<>();
                cells.add(result.getRunId());
                cells.add(result.getCaseId());
                cells.add(result.getCaseType());
                cells.add(String.valueOf(result.getExpectedAnswerable()));
                cells.add(bool(r == null ? null : r.hitAt1()));
                cells.add(bool(r == null ? null : r.hitAt3()));
                cells.add(bool(r == null ? null : r.hitAt5()));
                cells.add(num(r == null ? null : r.recallAt5()));
                cells.add(num(r == null ? null : r.recallAt10()));
                cells.add(num(r == null ? null : r.precisionAt5()));
                cells.add(num(r == null ? null : r.precisionAt10()));
                cells.add(num(r == null ? null : r.mrr()));
                cells.add(bool(r == null ? null : r.sourceHitAt5()));
                cells.add(bool(r == null ? null : r.permissionLeak()));
                cells.add(bool(i == null ? null : i.dbEsConsistent()));
                cells.add(num(i == null ? null : i.contextualizedContentRate()));
                cells.add(bool(r == null ? null : r.noAnswerFalsePositive()));
                cells.add(String.valueOf(result.getRetrievalLatencyMs()));
                cells.add(bool(a == null ? null : a.answerGenerated()));
                cells.add(bool(a == null ? null : a.mustContainPass()));
                cells.add(bool(a == null ? null : a.mustNotContainPass()));
                cells.add(bool(a == null ? null : a.sourceReferencePass()));
                cells.add(bool(a == null ? null : a.refusalPass()));
                cells.add(result.getErrorMessage());
                writer.write(csv(cells));
                writer.newLine();
            }
        }
    }

    private void writeMarkdown(List<EvalResult> results, Path path,
                               EvalProperties properties, Path ragasInputPath) throws Exception {
        long total = results.size();
        long errors = results.stream().filter(r -> r.getErrorMessage() != null).count();
        double hitAt5 = avg(results.stream()
                .map(EvalResult::getRetrieval)
                .filter(r -> r != null && r.hitAt5())
                .count(), total);
        double recallAt10 = avgMetric(results, r -> r.recallAt10());
        double mrr = avgMetric(results, RetrievalMetricResult::mrr);
        long permissionLeaks = results.stream()
                .map(EvalResult::getRetrieval)
                .filter(r -> r != null && r.permissionLeak())
                .count();
        long noAnswerFalsePositives = results.stream()
                .map(EvalResult::getRetrieval)
                .filter(r -> r != null && r.noAnswerFalsePositive())
                .count();
        long inconsistent = results.stream()
                .map(EvalResult::getIngestion)
                .filter(i -> i != null && !i.dbEsConsistent() && i.documentVectorCount() > 0)
                .count();

        StringBuilder md = new StringBuilder();
        md.append("# ArchiveMind RAG 二期离线评测报告\n\n");
        md.append("- Run ID: `").append(properties.getRunId()).append("`\n");
        md.append("- Case 数: ").append(total).append("\n");
        md.append("- 错误 Case: ").append(errors).append("\n");
        md.append("- RAGAS 输入: `").append(ragasInputPath).append("`\n\n");

        md.append("## 总览指标\n\n");
        md.append("| 指标 | 当前值 | 建议阈值 |\n");
        md.append("| --- | ---: | ---: |\n");
        md.append("| Hit@5 | ").append(DF.format(hitAt5)).append(" | ")
                .append(DF.format(properties.getHitAt5Threshold())).append(" |\n");
        md.append("| Recall@10 | ").append(DF.format(recallAt10)).append(" | ")
                .append(DF.format(properties.getRecallAt10Threshold())).append(" |\n");
        md.append("| MRR | ").append(DF.format(mrr)).append(" | ")
                .append(DF.format(properties.getMrrThreshold())).append(" |\n");
        md.append("| Permission Leak Count | ").append(permissionLeaks).append(" | 0 |\n");
        md.append("| No-answer False Positive Count | ").append(noAnswerFalsePositives).append(" | 0 |\n");
        md.append("| DB/ES Inconsistent Count | ").append(inconsistent).append(" | 0 |\n\n");

        md.append("## BadCase\n\n");
        md.append("| Case | 类型 | 首个命中排名 | 错误 |\n");
        md.append("| --- | --- | ---: | --- |\n");
        results.stream()
                .filter(this::isBadCase)
                .limit(50)
                .forEach(r -> md.append("| ")
                        .append(escapeMd(r.getCaseId())).append(" | ")
                        .append(escapeMd(r.getCaseType())).append(" | ")
                        .append(r.getRetrieval() == null ? -1 : r.getRetrieval().firstRelevantRank()).append(" | ")
                        .append(escapeMd(r.getErrorMessage() == null ? classify(r) : r.getErrorMessage()))
                        .append(" |\n"));

        md.append("\n## 下一步\n\n");
        md.append("1. 对 BadCase 按 `PARSE_BAD / CHUNK_BAD / CONTEXT_BAD / BM25_BAD / RERANK_BAD / GENERATION_BAD` 继续人工归因。\n");
        md.append("2. 运行 `eval/scripts/run_ragas_eval.py` 生成 RAGAS 分数，并合并到本报告。\n");
        md.append("3. 固定同一批 case 做 A/B/C/D 消融实验，比较结构感知 chunk 和 Contextual Retrieval 的收益。\n");

        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private boolean isBadCase(EvalResult result) {
        if (result.getErrorMessage() != null) {
            return true;
        }
        RetrievalMetricResult r = result.getRetrieval();
        if (r == null) {
            return true;
        }
        return result.getExpectedAnswerable()
                ? !r.hitAt5() || r.permissionLeak()
                : r.permissionLeak() || r.noAnswerFalsePositive();
    }

    private String classify(EvalResult result) {
        RetrievalMetricResult r = result.getRetrieval();
        IngestionCheckResult i = result.getIngestion();
        if (r != null && r.permissionLeak()) {
            return "PERMISSION_BAD";
        }
        if (r != null && r.noAnswerFalsePositive()) {
            return "NO_ANSWER_BAD";
        }
        if (i != null && !i.dbEsConsistent() && i.documentVectorCount() > 0) {
            return "INDEX_CONSISTENCY_BAD";
        }
        if (r != null && !r.hitAt5()) {
            return "RETRIEVAL_BAD";
        }
        return "UNKNOWN";
    }

    private double avg(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 1.0 / denominator;
    }

    private double avgMetric(List<EvalResult> results, MetricExtractor extractor) {
        return results.stream()
                .map(EvalResult::getRetrieval)
                .filter(r -> r != null)
                .mapToDouble(extractor::value)
                .average()
                .orElse(0.0);
    }

    private String csv(List<String> values) {
        return values.stream().map(this::escapeCsv).reduce((a, b) -> a + "," + b).orElse("");
    }

    private String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private String escapeMd(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String bool(Boolean value) {
        return value == null ? "" : value.toString();
    }

    private String num(Double value) {
        return value == null ? "" : DF.format(value);
    }

    @FunctionalInterface
    private interface MetricExtractor {
        double value(RetrievalMetricResult result);
    }
}
