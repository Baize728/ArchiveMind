package com.zyh.archivemind.eval;

import com.zyh.archivemind.entity.SearchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnswerRuleChecker {

    public AnswerCheckResult check(EvalCase evalCase, String response, List<SearchResult> retrieved) {
        if (response == null || response.isBlank()) {
            return AnswerCheckResult.notGenerated();
        }

        boolean mustContainPass = evalCase.mustContainOrEmpty().stream()
                .allMatch(token -> token != null && !token.isBlank() && response.contains(token));
        boolean mustNotContainPass = evalCase.mustNotContainOrEmpty().stream()
                .noneMatch(token -> token != null && !token.isBlank() && response.contains(token));
        boolean refusalPass = evalCase.expectedAnswerableValue()
                ? !looksLikeRefusal(response)
                : looksLikeRefusal(response);
        boolean sourceReferencePass = sourceReferencePass(response, retrieved);

        return new AnswerCheckResult(true, mustContainPass, mustNotContainPass,
                sourceReferencePass, refusalPass);
    }

    private boolean looksLikeRefusal(String response) {
        return response.contains("暂无相关信息")
                || response.contains("未找到")
                || response.contains("无法确认")
                || response.contains("没有足够信息")
                || response.contains("无法根据现有资料");
    }

    private boolean sourceReferencePass(String response, List<SearchResult> retrieved) {
        if (!response.contains("来源#") && !response.contains("[1]")) {
            return false;
        }
        return retrieved != null && retrieved.stream().anyMatch(r -> r.getFileName() != null && !r.getFileName().isBlank());
    }
}
