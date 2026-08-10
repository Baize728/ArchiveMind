package com.zyh.archivemind.eval;

import com.zyh.archivemind.entity.SearchResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class AnswerRuleChecker {

    private static final Pattern SENTENCE_SPLITTER = Pattern.compile("[。！？!?\\n]+");

    public AnswerCheckResult check(EvalCase evalCase, String response, List<SearchResult> retrieved) {
        if (response == null || response.isBlank()) {
            return AnswerCheckResult.notGenerated();
        }

        boolean mustContainPass = evalCase.mustContainOrEmpty().stream()
                .allMatch(token -> matchesAnyRequiredTerm(response, token));
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
        for (String sentence : SENTENCE_SPLITTER.split(response)) {
            String normalized = sentence.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            boolean hasRefusal = normalized.contains("暂无相关信息")
                    || normalized.contains("未找到")
                    || normalized.contains("无法确认")
                    || normalized.contains("没有足够信息")
                    || normalized.contains("无法根据现有资料");
            boolean hasPartialQualifier = normalized.contains("部分")
                    || normalized.contains("具体")
                    || normalized.contains("更多")
                    || normalized.contains("展开")
                    || normalized.contains("细节")
                    || normalized.contains("只能确认")
                    || normalized.contains("不能补充");
            if (hasRefusal && !hasPartialQualifier) {
                return true;
            }
        }
        return false;
    }

    private boolean sourceReferencePass(String response, List<SearchResult> retrieved) {
        if (!response.contains("来源#") && !response.contains("[1]")) {
            return false;
        }
        return retrieved != null && retrieved.stream().anyMatch(r -> r.getFileName() != null && !r.getFileName().isBlank());
    }

    private boolean matchesAnyRequiredTerm(String response, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String[] alternatives = token.split("\\|");
        for (String alternative : alternatives) {
            if (matchesRequiredPhrase(response, alternative.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRequiredPhrase(String response, String phrase) {
        if (phrase.isBlank()) {
            return true;
        }
        String[] terms = phrase.split("[,，、]");
        for (String term : terms) {
            if (!term.isBlank() && !response.contains(term.trim())) {
                return false;
            }
        }
        return true;
    }
}
