package com.zyh.archivemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "eval")
@Data
public class EvalProperties {
    private Weights weights = new Weights();
    private Judge judge = new Judge();
    private Regression regression = new Regression();
    private Task task = new Task();

    @Data
    public static class Weights {
        private double rule = 0.5;
        private double judge = 0.3;
        private double feedback = 0.2;
    }

    @Data
    public static class Judge {
        private JudgeWeights weights = new JudgeWeights();
    }

    @Data
    public static class JudgeWeights {
        private double faithfulness = 0.4;
        private double relevance = 0.3;
        private double completeness = 0.2;
        private double naturalness = 0.1;
        private double faithfulnessWithCorrectness = 0.35;
        private double relevanceWithCorrectness = 0.25;
        private double completenessWithCorrectness = 0.15;
        private double correctness = 0.15;
        private double naturalnessWithCorrectness = 0.10;
    }

    @Data
    public static class Regression {
        private int sampleSize = 100;
        private double passThreshold = 80;
        private double baselineDegradation = 3;
    }

    @Data
    public static class Task {
        private int retentionHours = 24;
    }
}
