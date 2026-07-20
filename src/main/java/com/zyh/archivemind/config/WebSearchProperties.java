package com.zyh.archivemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "web-search")
@Data
public class WebSearchProperties {

    /** 搜索提供商: zhipu | serpapi | searxng，留空自动检测 */
    private String provider;

    /** 智谱 GLM API Key */
    private String glmApiKey;

    /** SerpAPI Key */
    private String serpApiKey;

    /** SearXNG 实例地址 */
    private String searxngUrl;

    /** 智谱搜索引擎类型 */
    private String zhipuEngine = "search_std";

    /** 搜索返回条数上限 */
    private int searchTopK = 5;

    /** 抓取内容最大字符数 */
    private int fetchMaxChars = 8000;

    /** 抓取超时秒数 */
    private int fetchTimeoutSeconds = 15;
}
