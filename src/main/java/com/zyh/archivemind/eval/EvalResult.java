package com.zyh.archivemind.eval;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EvalResult {
    private String runId;
    private String caseId;
    private String caseType;
    private String question;
    private String reference;
    private String userId;
    private String orgTag;
    private Boolean expectedAnswerable;
    private String goldFileMd5;
    private List<Integer> goldChunkIds;
    private List<RetrievedContext> retrievedContexts;
    private String response;
    private String errorMessage;
    private long retrievalLatencyMs;
    private long answerLatencyMs;
    private IngestionCheckResult ingestion;
    private RetrievalMetricResult retrieval;
    private AnswerCheckResult answerCheck;
    private Map<String, Object> costMetrics;
}
