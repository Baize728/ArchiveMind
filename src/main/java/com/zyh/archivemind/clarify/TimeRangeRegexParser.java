package com.zyh.archivemind.clarify;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * timeRange 分层正则解析器（T1-2，Q8）。
 *
 * 分层匹配：
 * 1. 相对时间：去年/上个月/今年/前年
 * 2. 绝对时间：2024年/2024Q3/2024-01
 * 3. 模糊时间：最新版/最近的 → latest
 *
 * 缺失不阻塞（Q8：timeRange 非必填，不强行 LLM）。
 */
@Component
public class TimeRangeRegexParser {

    // 相对时间
    private static final Pattern RELATIVE_PATTERN = Pattern.compile(
            "(前年|去年|今年|上个月|上月|上季度|去年这个时候)");

    // 绝对时间：2024年 / 2024Q3 / 2024-01
    private static final Pattern ABSOLUTE_PATTERN = Pattern.compile(
            "(20\\d{2}(?:年|Q[1-4]|-[01]\\d)?)");

    // 模糊时间
    private static final Pattern FUZZY_PATTERN = Pattern.compile(
            "(最新版|最新的|最近的|近期)");

    /**
     * 从用户输入解析 timeRange。
     *
     * @return 时间范围字符串；未命中返回 null
     */
    public String parse(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        // 优先匹配绝对时间（最精确）
        Matcher m = ABSOLUTE_PATTERN.matcher(userInput);
        if (m.find()) {
            return m.group(1);
        }

        // 相对时间
        m = RELATIVE_PATTERN.matcher(userInput);
        if (m.find()) {
            return m.group(1);
        }

        // 模糊时间 → latest
        m = FUZZY_PATTERN.matcher(userInput);
        if (m.find()) {
            return "latest";
        }

        return null;
    }
}
