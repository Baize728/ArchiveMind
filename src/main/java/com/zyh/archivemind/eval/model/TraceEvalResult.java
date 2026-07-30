package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceEvalResult {
    private String traceId;
    private String conversationId;
    private LocalDateTime createdAt;
    private Double score;           // total, percent
    private Double ruleScore;       // percent
    private Double judgeScore;      // percent
    private Double feedbackScore;   // percent
    private Map<String, Double> metrics;
    private Map<String, Object> detail;
}
