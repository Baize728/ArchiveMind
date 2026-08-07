package com.zyh.archivemind.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eval.runner")
@Data
public class EvalProperties {

    /**
     * 是否在应用启动后执行离线评测。
     * 默认关闭，避免影响正常服务启动。
     */
    private boolean enabled = false;

    private String casesPath = "eval/cases/rag_eval_cases.json";

    private String outputDir = "eval/outputs";

    private String runId = "";

    private int topK = 10;

    private int ragasContextTopK = 5;

    private boolean generateAnswers = false;

    private int answerTopK = 5;

    private int answerTimeoutSeconds = 90;

    private int maxContextChars = 8000;

    private boolean exitOnComplete = false;

    private double hitAt5Threshold = 0.85;

    private double recallAt10Threshold = 0.80;

    private double mrrThreshold = 0.65;
}
