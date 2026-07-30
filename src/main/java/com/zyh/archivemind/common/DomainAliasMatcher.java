package com.zyh.archivemind.common;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * Domain 别名匹配公共工具类（T1-2，Q23）。
 *
 * 加载 domain-aliases.yml，提供同义词包含匹配。
 * 被 IntentReviseService（T1-1 升格判定）和 SlotExtractor（T1-2 槽位抽取）共用——
 * 消除 T1-1 对 T1-2 的反向依赖（Q23 决策）。
 *
 * 层次关系：
 *   common/DomainAliasMatcher          ← 公共工具，无业务依赖
 *     ↑                ↑
 *   intent/IntentReviseService    clarify/SlotExtractor
 */
@Component
public class DomainAliasMatcher {

    private static final Logger logger = LoggerFactory.getLogger(DomainAliasMatcher.class);

    /** alias → domain 映射（如 "报销" → "finance"） */
    private Map<String, String> aliasToDomain = new HashMap<>();

    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("domain-aliases.yml")) {
            if (is == null) {
                logger.warn("domain-aliases.yml 未找到，DomainAliasMatcher 将无法匹配");
                return;
            }
            // 解析 YAML 简单格式：aliases:\n  finance: [报销, 财务, ...]
            parseYaml(is);
            logger.info("DomainAliasMatcher 加载完成，共 {} 个别名", aliasToDomain.size());
        } catch (Exception e) {
            logger.warn("加载 domain-aliases.yml 失败: {}", e.getMessage());
        }
    }

    private void parseYaml(InputStream is) {
        try {
            List<String> lines = new ArrayList<>();
            new String(is.readAllBytes()).lines().forEach(lines::add);
            String currentDomain = null;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.endsWith(":") && !line.startsWith("[")) {
                    // 顶层 key 如 "aliases:"
                    continue;
                }
                // "  finance: [报销, 财务, ...]" 格式
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String valuePart = line.substring(colonIdx + 1).trim();
                    if (valuePart.startsWith("[")) {
                        // 解析列表 [a, b, c]
                        String listContent = valuePart.substring(1, valuePart.lastIndexOf(']'));
                        String[] aliases = listContent.split(",");
                        for (String alias : aliases) {
                            String trimmed = alias.trim();
                            if (!trimmed.isEmpty()) {
                                aliasToDomain.put(trimmed, key);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析 domain-aliases.yml 失败: {}", e.getMessage());
        }
    }

    /**
     * 匹配用户输入中的 domain 别名，返回 domain 值。
     * 多命中取词数最多的别名（如 "差旅报销" 优先于 "报销"）。
     *
     * @return 命中的 domain 值；未命中返回 Optional.empty()
     */
    public Optional<String> match(String userInput) {
        if (userInput == null || userInput.isBlank() || aliasToDomain.isEmpty()) {
            return Optional.empty();
        }
        String bestAlias = null;
        for (String alias : aliasToDomain.keySet()) {
            if (userInput.contains(alias)) {
                if (bestAlias == null || alias.length() > bestAlias.length()) {
                    bestAlias = alias;
                }
            }
        }
        return bestAlias != null ? Optional.of(aliasToDomain.get(bestAlias)) : Optional.empty();
    }
}
