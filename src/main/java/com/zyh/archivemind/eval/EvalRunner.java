package com.zyh.archivemind.eval;

import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Component
@ConditionalOnProperty(prefix = "eval.runner", name = "enabled", havingValue = "true")
public class EvalRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalProperties properties;
    private final EvalCaseLoader caseLoader;
    private final IngestionConsistencyChecker ingestionChecker;
    private final HybridSearchService hybridSearchService;
    private final RetrievalMetricCalculator retrievalMetricCalculator;
    private final EvalAnswerGenerator answerGenerator;
    private final AnswerRuleChecker answerRuleChecker;
    private final RagasInputExporter ragasInputExporter;
    private final EvalReportWriter reportWriter;

    public EvalRunner(EvalProperties properties,
                      EvalCaseLoader caseLoader,
                      IngestionConsistencyChecker ingestionChecker,
                      HybridSearchService hybridSearchService,
                      RetrievalMetricCalculator retrievalMetricCalculator,
                      EvalAnswerGenerator answerGenerator,
                      AnswerRuleChecker answerRuleChecker,
                      RagasInputExporter ragasInputExporter,
                      EvalReportWriter reportWriter) {
        this.properties = properties;
        this.caseLoader = caseLoader;
        this.ingestionChecker = ingestionChecker;
        this.hybridSearchService = hybridSearchService;
        this.retrievalMetricCalculator = retrievalMetricCalculator;
        this.answerGenerator = answerGenerator;
        this.answerRuleChecker = answerRuleChecker;
        this.ragasInputExporter = ragasInputExporter;
        this.reportWriter = reportWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getRunId() == null || properties.getRunId().isBlank()) {
            properties.setRunId("eval-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + UUID.randomUUID().toString().substring(0, 8));
        }

        Path casesPath = Path.of(properties.getCasesPath());
        Path outputDir = Path.of(properties.getOutputDir());
        logger.info("开始 ArchiveMind 离线评测: runId={}, cases={}, outputDir={}, topK={}, generateAnswers={}",
                properties.getRunId(), casesPath.toAbsolutePath(), outputDir.toAbsolutePath(),
                properties.getTopK(), properties.isGenerateAnswers());

        List<EvalCase> cases = caseLoader.load(casesPath);
        List<EvalResult> results = cases.stream().map(this::runCase).toList();
        Path ragasInput = ragasInputExporter.export(results, outputDir, properties.getRagasContextTopK());
        reportWriter.write(results, outputDir, properties, ragasInput);

        long failures = results.stream().filter(r -> r.getErrorMessage() != null).count();
        logger.info("ArchiveMind 离线评测完成: runId={}, cases={}, failures={}, report={}",
                properties.getRunId(), results.size(), failures, outputDir.resolve("eval_report.md").toAbsolutePath());

        if (properties.isExitOnComplete()) {
            int exitCode = failures == 0 ? 0 : 1;
            logger.info("eval.runner.exit-on-complete=true, exitCode={}", exitCode);
            System.exit(exitCode);
        }
    }

    private EvalResult runCase(EvalCase evalCase) {
        EvalResult result = new EvalResult();
        result.setRunId(properties.getRunId());
        result.setCaseId(evalCase.effectiveCaseId());
        result.setCaseType(evalCase.effectiveCaseType());
        result.setQuestion(evalCase.question());
        result.setReference(evalCase.reference());
        result.setUserId(evalCase.effectiveUserId());
        result.setOrgTag(evalCase.orgTag());
        result.setExpectedAnswerable(evalCase.expectedAnswerableValue());
        result.setGoldFileMd5(evalCase.goldFileMd5());
        result.setGoldChunkIds(evalCase.goldChunkIdsOrEmpty());

        try {
            IngestionCheckResult ingestion = ingestionChecker.check(evalCase);
            result.setIngestion(ingestion);

            long retrievalStart = System.currentTimeMillis();
            List<SearchResult> searchResults = hybridSearchService.searchWithPermission(
                    evalCase.question(), evalCase.effectiveUserId(), properties.getTopK());
            result.setRetrievalLatencyMs(System.currentTimeMillis() - retrievalStart);
            result.setRetrievedContexts(toRetrievedContexts(searchResults));
            result.setRetrieval(retrievalMetricCalculator.calculate(evalCase, searchResults, properties.getTopK()));

            long answerStart = System.currentTimeMillis();
            String response = properties.isGenerateAnswers()
                    ? answerGenerator.generate(evalCase, searchResults)
                    : "";
            result.setAnswerLatencyMs(System.currentTimeMillis() - answerStart);
            result.setResponse(response);
            result.setAnswerCheck(answerRuleChecker.check(evalCase, response, searchResults));
            result.setCostMetrics(costMetrics(searchResults, response));
        } catch (Exception e) {
            logger.error("评测 case 执行失败: caseId={}, question={}",
                    evalCase.effectiveCaseId(), evalCase.question(), e);
            result.setErrorMessage(e.getMessage());
            result.setRetrievedContexts(List.of());
            result.setResponse("");
            result.setAnswerCheck(AnswerCheckResult.notGenerated());
            result.setCostMetrics(Map.of());
        }

        return result;
    }

    private List<RetrievedContext> toRetrievedContexts(List<SearchResult> results) {
        if (results == null) {
            return List.of();
        }
        return IntStream.range(0, results.size())
                .mapToObj(i -> {
                    SearchResult r = results.get(i);
                    return new RetrievedContext(
                            i + 1,
                            r.getFileMd5(),
                            r.getChunkId(),
                            r.getFileName(),
                            r.getTextContent(),
                            r.getContextualizedContent(),
                            r.getScore(),
                            r.getUserId(),
                            r.getOrgTag(),
                            r.getIsPublic(),
                            r.getDocTitle(),
                            r.getHeadingPath(),
                            r.getBlockType()
                    );
                })
                .toList();
    }

    private Map<String, Object> costMetrics(List<SearchResult> results, String response) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("retrievedCount", results == null ? 0 : results.size());
        metrics.put("responseChars", response == null ? 0 : response.length());
        metrics.put("estimatedResponseTokens", response == null ? 0 : (int) Math.ceil(response.length() / 4.0));
        metrics.put("answerGenerationEnabled", properties.isGenerateAnswers());
        return metrics;
    }
}
