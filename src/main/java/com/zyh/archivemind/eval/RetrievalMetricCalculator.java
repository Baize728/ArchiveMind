package com.zyh.archivemind.eval;

import com.zyh.archivemind.entity.SearchResult;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RetrievalMetricCalculator {

    public RetrievalMetricResult calculate(EvalCase evalCase, List<SearchResult> results, int topK) {
        if (results == null || results.isEmpty()) {
            return new RetrievalMetricResult(false, false, false,
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    false, hasPermissionLeak(evalCase, results), false, -1, 0);
        }

        if (!evalCase.expectedAnswerableValue()) {
            return new RetrievalMetricResult(false, false, false,
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    false, hasPermissionLeak(evalCase, results), true, -1, 0);
        }

        int firstRelevantRank = -1;
        Set<Integer> matchedAt5 = new HashSet<>();
        Set<Integer> matchedAt10 = new HashSet<>();
        int relevantAt5 = 0;
        int relevantAt10 = 0;

        int limit = Math.min(results.size(), Math.max(topK, 10));
        for (int i = 0; i < limit; i++) {
            SearchResult result = results.get(i);
            boolean relevant = isRelevant(evalCase, result);
            int rank = i + 1;
            if (relevant && firstRelevantRank < 0) {
                firstRelevantRank = rank;
            }
            if (rank <= 5 && relevant) {
                relevantAt5++;
                if (result.getChunkId() != null) {
                    matchedAt5.add(result.getChunkId());
                }
            }
            if (rank <= 10 && relevant) {
                relevantAt10++;
                if (result.getChunkId() != null) {
                    matchedAt10.add(result.getChunkId());
                }
            }
        }

        int goldCount = goldDenominator(evalCase);
        double recallAt5 = goldCount == 0 ? 0.0 : matchedAt5.size() * 1.0 / goldCount;
        double recallAt10 = goldCount == 0 ? 0.0 : matchedAt10.size() * 1.0 / goldCount;
        double precisionAt5 = relevantAt5 * 1.0 / Math.max(1, Math.min(5, results.size()));
        double precisionAt10 = relevantAt10 * 1.0 / Math.max(1, Math.min(10, results.size()));
        double mrr = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0;

        return new RetrievalMetricResult(
                firstRelevantRank == 1,
                firstRelevantRank > 0 && firstRelevantRank <= 3,
                firstRelevantRank > 0 && firstRelevantRank <= 5,
                recallAt5,
                recallAt10,
                precisionAt5,
                precisionAt10,
                mrr,
                sourceHitAtK(evalCase, results, 5),
                hasPermissionLeak(evalCase, results),
                false,
                firstRelevantRank,
                matchedAt10.size()
        );
    }

    private boolean isRelevant(EvalCase evalCase, SearchResult result) {
        if (result == null) {
            return false;
        }
        boolean fileMatches = evalCase.goldFileMd5() == null || evalCase.goldFileMd5().isBlank()
                || evalCase.goldFileMd5().equals(result.getFileMd5());
        List<Integer> goldChunkIds = evalCase.goldChunkIdsOrEmpty();
        if (!goldChunkIds.isEmpty()) {
            return fileMatches && result.getChunkId() != null && goldChunkIds.contains(result.getChunkId());
        }
        return fileMatches && evalCase.goldFileMd5() != null && !evalCase.goldFileMd5().isBlank();
    }

    private int goldDenominator(EvalCase evalCase) {
        if (!evalCase.goldChunkIdsOrEmpty().isEmpty()) {
            return evalCase.goldChunkIdsOrEmpty().size();
        }
        return evalCase.goldFileMd5() == null || evalCase.goldFileMd5().isBlank() ? 0 : 1;
    }

    private boolean sourceHitAtK(EvalCase evalCase, List<SearchResult> results, int k) {
        if (evalCase.goldFileMd5() == null || evalCase.goldFileMd5().isBlank()) {
            return false;
        }
        return results.stream()
                .limit(k)
                .anyMatch(r -> evalCase.goldFileMd5().equals(r.getFileMd5()));
    }

    private boolean hasPermissionLeak(EvalCase evalCase, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        return results.stream().anyMatch(r -> !isVisibleToCaseUser(evalCase, r));
    }

    private boolean isVisibleToCaseUser(EvalCase evalCase, SearchResult result) {
        if (result == null) {
            return false;
        }
        if (result.getUserId() != null && result.getUserId().equals(evalCase.effectiveUserId())) {
            return true;
        }
        if (Boolean.TRUE.equals(result.getIsPublic())) {
            return true;
        }
        return evalCase.orgTag() != null
                && !evalCase.orgTag().isBlank()
                && evalCase.orgTag().equals(result.getOrgTag());
    }
}
