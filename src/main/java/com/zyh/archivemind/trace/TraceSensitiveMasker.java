package com.zyh.archivemind.trace;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 落库前脱敏：手机号 / 身份证 / 邮箱 正则替换为 ***，
 * 并对敏感词（密码 / token / secret 等凭据）命中 "key=value" 形式的值脱敏。
 * 满足 plan.md T0-1「含敏感词的用户输入在落库前被脱敏」。
 */
public class TraceSensitiveMasker {

    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final boolean enabled;
    private final List<String> sensitiveKeywords;

    public TraceSensitiveMasker(boolean enabled, List<String> sensitiveKeywords) {
        this.enabled = enabled;
        this.sensitiveKeywords = sensitiveKeywords == null ? List.of() : sensitiveKeywords;
    }

    public String mask(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = PHONE.matcher(result).replaceAll("***");
        result = ID_CARD.matcher(result).replaceAll("***");
        result = EMAIL.matcher(result).replaceAll("***");
        for (String kw : sensitiveKeywords) {
            if (kw == null || kw.isEmpty()) {
                continue;
            }
            // 命中 key=value / key: value 形式，仅替换值部分
            result = result.replaceAll("(?i)" + Pattern.quote(kw) + "\\s*[:=]\\s*\\S+",
                    kw + "=***");
        }
        return result;
    }
}
