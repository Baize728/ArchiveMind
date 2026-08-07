package com.zyh.archivemind.eval;

public record RetrievalMetricResult(
        boolean hitAt1,
        boolean hitAt3,
        boolean hitAt5,
        double recallAt5,
        double recallAt10,
        double precisionAt5,
        double precisionAt10,
        double mrr,
        boolean sourceHitAt5,
        boolean permissionLeak,
        boolean noAnswerFalsePositive,
        int firstRelevantRank,
        int matchedGoldCountAt10
) {
}
