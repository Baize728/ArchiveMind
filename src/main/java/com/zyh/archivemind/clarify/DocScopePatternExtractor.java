package com.zyh.archivemind.clarify;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * docScope 正则+句式提取器（T1-2，Q8）。
 *
 * 匹配方式：
 * 1. 正则：[\u4e00-\u9fa5\w]+(合同|报告|手册|规范|制度)
 * 2. 句式：那份(.+?)/这份(.+?)/刚才的(.+?)
 *
 * 多命中取最长。
 */
@Component
public class DocScopePatternExtractor {

    private static final Pattern DOC_TYPE_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fa5\\w]+(?:合同|报告|手册|规范|制度)");

    private static final Pattern[] SENTENCE_PATTERNS = {
            Pattern.compile("那份(.+?)(?:的|是什么|内容)"),
            Pattern.compile("这份(.+?)(?:的|是什么|内容)"),
            Pattern.compile("刚才的(.+?)(?:的|是什么|内容)")
    };

    /**
     * 从用户输入提取 docScope。
     *
     * @return 命中的文档范围；未命中返回 null
     */
    public String extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        String best = null;

        // 1. 正则匹配文档类型
        Matcher m = DOC_TYPE_PATTERN.matcher(userInput);
        while (m.find()) {
            String matched = m.group();
            if (best == null || matched.length() > best.length()) {
                best = matched;
            }
        }

        // 2. 句式匹配"那份XX/这份XX/刚才的XX"
        for (Pattern p : SENTENCE_PATTERNS) {
            Matcher sm = p.matcher(userInput);
            if (sm.find()) {
                String matched = sm.group(1);
                if (matched != null && !matched.isBlank()) {
                    if (best == null || matched.length() > best.length()) {
                        best = matched.trim();
                    }
                }
            }
        }

        return best;
    }
}
