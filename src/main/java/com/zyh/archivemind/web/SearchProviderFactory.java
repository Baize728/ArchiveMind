package com.zyh.archivemind.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 按显式配置参数选择 SearchProvider 实现。
 *
 * 自动选择优先级（未显式 SEARCH_PROVIDER 时）：
 * <ol>
 *   <li>有 {@code GLM_API_KEY} → zhipu（智谱 Web Search，与 GLM 推理共用 Key，国内首选）</li>
 *   <li>有 {@code SERPAPI_KEY} → serpapi（国际通用，付费即开即用）</li>
 *   <li>有 {@code SEARXNG_URL} → searxng（开源自托管，免费）</li>
 *   <li>都没有 → 占位 zhipu provider，isReady() 为 false，由调用方提示用户</li>
 * </ol>
 *
 * 显式 {@code SEARCH_PROVIDER}（zhipu / serpapi / searxng）会跳过自动判断。
 */
public final class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private SearchProviderFactory() {}

    /**
     * 无参创建，返回占位 provider（isReady=false），由调用方提示用户配置。
     */
    public static SearchProvider create() {
        log.info("SearchProvider: 未配置，返回占位 provider");
        return new ZhipuSearchProvider(null, "search_std");
    }

    /**
     * 根据显式配置参数创建 SearchProvider（由 Spring 从 application.yml 注入）。
     */
    public static SearchProvider create(String provider, String glmKey,
                                         String serpKey, String searxngUrl,
                                         String zhipuEngine) {
        String chosen = pickProvider(provider, glmKey, serpKey, searxngUrl);
        log.info("SearchProvider chosen: {}", chosen);
        return switch (chosen) {
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            default -> new ZhipuSearchProvider(glmKey,
                    zhipuEngine != null && !zhipuEngine.isBlank()
                            ? zhipuEngine : "search_std");
        };
    }

    static String pickProvider(String explicit, String glmKey, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (glmKey != null && !glmKey.isBlank()) {
            return "zhipu";
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        return "zhipu";
    }
}
