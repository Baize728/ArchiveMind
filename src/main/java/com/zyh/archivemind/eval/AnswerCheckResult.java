package com.zyh.archivemind.eval;

public record AnswerCheckResult(
        boolean answerGenerated,
        boolean mustContainPass,
        boolean mustNotContainPass,
        boolean sourceReferencePass,
        boolean refusalPass
) {
    public static AnswerCheckResult notGenerated() {
        return new AnswerCheckResult(false, false, true, false, false);
    }
}
