package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalReport {
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private int totalTraces;
    private int labeledTraces;
    private double labelCoverage;
    private Double averageScore;
    private boolean pass;
    private String passReason;
    private Map<String, MetricStat> metricAverages;
    private List<TraceEvalResult> details;
    private List<FailedTrace> failedTraces;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedTrace {
        private String traceId;
        private double score;
        private String failReason;
    }
}
