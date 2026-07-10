package com.zyh.archivemind.Tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.zyh.archivemind.Tool.Tool;
import com.zyh.archivemind.web.FetchResult;
import com.zyh.archivemind.web.HtmlExtractor;
import com.zyh.archivemind.web.NetworkPolicy;
import com.zyh.archivemind.web.WebFetcher;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class webFetchTool implements Tool {

    private NetworkPolicy networkPolicy;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "抓取指定 URL，提取正文转 Markdown。适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。";
    }

    @Override
    public JsonNode getParameterSchema() {
        return Tool.buildSchema(
                new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                new Param("max_char", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
        );
    }

    private synchronized NetworkPolicy getNetworkPolicy() {
        if (networkPolicy == null) {
            networkPolicy =  new NetworkPolicy();
        }
        return networkPolicy;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    @Override
    public Tool.ToolResult execute(ToolContext context, Map<String, Object> params) {
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return Tool.ToolResult.failure("URL 不能为空");
        }

        int maxChars = 8000;
        Object maxCharObj = params.get("max_char");
        if (maxCharObj instanceof Number) {
            maxChars = ((Number) maxCharObj).intValue();
        }

        NetworkPolicy policy = getNetworkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return Tool.ToolResult.failure("❌ 网络访问被拒绝: " + denyReason);
        }

        String rateReason = policy.acquire();
        if (rateReason != null) {
            return Tool.ToolResult.failure("❌" + rateReason);
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return ToolResult.success(formatFetchResult(result));
        } catch (Exception e) {
            return Tool.ToolResult.failure("抓取失败: " + e.getMessage());
        }
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }
}
