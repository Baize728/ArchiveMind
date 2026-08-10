package com.zyh.archivemind.eval;

import java.util.List;

public record EvalCase(
        String caseId,
        String caseType,
        String question,
        String reference,
        String goldFileMd5,
        List<Integer> goldChunkIds,
        List<String> referenceContexts,
        List<String> mustContain,
        List<String> mustNotContain,
        String userId,
        String orgTag,
        Boolean isPublic,
        Boolean expectedAnswerable
) {
    public String effectiveCaseId() {
        return isBlank(caseId) ? "case-" + Math.abs(question.hashCode()) : caseId;
    }

    public String effectiveCaseType() {
        return isBlank(caseType) ? "unknown" : caseType;
    }

    public String effectiveUserId() {
        return isBlank(userId) ? "1" : userId;
    }

    public boolean expectedAnswerableValue() {
        return expectedAnswerable == null || expectedAnswerable;
    }

    public List<Integer> goldChunkIdsOrEmpty() {
        return goldChunkIds == null ? List.of() : goldChunkIds;
    }

    public List<String> referenceContextsOrEmpty() {
        return referenceContexts == null ? List.of() : referenceContexts;
    }

    public List<String> mustContainOrEmpty() {
        return mustContain == null ? List.of() : mustContain;
    }

    public List<String> mustNotContainOrEmpty() {
        return mustNotContain == null ? List.of() : mustNotContain;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
