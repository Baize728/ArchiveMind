package com.zyh.archivemind.clarify;

import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import com.hankcs.hanlp.seg.common.Term;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * entity 名词短语提取器（T1-2，Q8/Q24）。
 *
 * 复用现有 HanLP StandardTokenizer（pom 已依赖，ParseService 同款用法，零新依赖）。
 * 分词后过滤停用词，取最长连续名词短语（nature 以 "n" 开头）。
 *
 * HanLP 词性 n 开头涵盖：n(名词)/nr(人名)/ns(地名)/nt(机构名)/nz(其他专名)——
 * 业务名词如"差旅报销""年假"都会归入 n 或 nz。
 */
@Component
public class EntityNounPhraseExtractor {

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "请问", "那个", "这个", "什么", "怎么", "为什么",
            "可以", "能", "帮", "我", "你", "他", "她", "它", "我们", "你们",
            "有", "没有", "在", "和", "与", "或", "及", "以及", "或者"
    );

    /**
     * 从用户输入提取最长连续名词短语。
     *
     * @return 名词短语（保持用户原话，不归一化）；未命中返回 null
     */
    public String extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        try {
            List<Term> terms = StandardTokenizer.segment(userInput);
            String longest = "";
            StringBuilder current = new StringBuilder();

            for (Term term : terms) {
                if (isStopword(term.word) || !isNoun(term.nature.toString())) {
                    // 非名词或停用词 → 断开当前短语
                    if (current.length() > longest.length()) {
                        longest = current.toString();
                    }
                    current.setLength(0);
                } else {
                    current.append(term.word);
                }
            }
            // 处理末尾
            if (current.length() > longest.length()) {
                longest = current.toString();
            }

            return longest.isEmpty() ? null : longest;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isStopword(String word) {
        return STOPWORDS.contains(word) || word.length() == 1 && !Character.isLetterOrDigit(word.charAt(0));
    }

    private boolean isNoun(String nature) {
        return nature != null && nature.startsWith("n");
    }
}
