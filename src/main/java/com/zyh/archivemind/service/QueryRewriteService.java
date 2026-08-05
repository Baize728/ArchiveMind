package com.zyh.archivemind.service;

import com.zyh.archivemind.client.RewriteLlmClient;
import com.zyh.archivemind.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Query Rewriting 服务：利用独立的 LLM 将多轮对话中的后续问题改写为语义完整的独立查询，
 * 解决指代消解、省略补全等问题，提升 RAG 检索召回准确率。
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    private final RewriteLlmClient rewriteLlmClient;
    private final AiProperties aiProperties;

    public QueryRewriteService(RewriteLlmClient rewriteLlmClient, AiProperties aiProperties) {
        this.rewriteLlmClient = rewriteLlmClient;
        this.aiProperties = aiProperties;
    }

    /**
     * 根据对话历史改写当前用户查询。
     *
     * @param currentQuery 用户当前输入的原始问题
     * @param history      对话历史（role/content 的 Map 列表）
     * @return 改写后的完整查询；如果改写失败或无需改写则返回原始查询
     */
    public String rewrite(String currentQuery, List<Map<String, String>> history) {
        AiProperties.Rewrite cfg = aiProperties.getRewrite();

        if (!cfg.isEnabled()) {
            logger.debug("Query Rewriting 未启用，返回原始查询");
            return currentQuery;
        }

        if (history == null || history.isEmpty()) {
            logger.debug("无对话历史，跳过改写");
            return currentQuery;
        }

        String deterministicFollowUp = rewriteAffirmativeFollowUp(currentQuery, history);
        if (deterministicFollowUp != null) {
            logger.info("Query Rewriting: [{}] -> [{}]", currentQuery, deterministicFollowUp);
            return deterministicFollowUp;
        }

        try {
            int maxMessages = cfg.getMaxHistoryRounds() * 2;
            List<Map<String, String>> recentHistory = history.size() > maxMessages
                    ? history.subList(history.size() - maxMessages, history.size())
                    : history;

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", cfg.getSystemPrompt()));

            StringBuilder contextBuilder = new StringBuilder("以下是对话历史：\n");
            for (Map<String, String> msg : recentHistory) {
                String role = "user".equals(msg.get("role")) ? "用户" : "助手";
                String content = msg.getOrDefault("content", "");
                contextBuilder.append(role).append("：").append(content).append("\n");
            }
            contextBuilder.append("\n用户最新的问题是：").append(currentQuery);
            contextBuilder.append("\n\n请将上述最新问题改写为一个语义完整的检索查询：");

            messages.add(Map.of("role", "user", "content", contextBuilder.toString()));

            logger.debug("Query Rewriting prompt: {}", contextBuilder.toString());

            String rewritten = rewriteLlmClient.chatSync(messages);

            if (rewritten == null || rewritten.isBlank()) {
                logger.warn("LLM 返回空结果，使用原始查询");
                return currentQuery;
            }

            logger.info("Query Rewriting: [{}] -> [{}]", currentQuery, rewritten);
            return rewritten;

        } catch (Exception e) {
            logger.error("Query Rewriting 失败，回退到原始查询: {}", e.getMessage(), e);
            return currentQuery;
        }
    }

    private String rewriteAffirmativeFollowUp(String currentQuery, List<Map<String, String>> history) {
        if (!isAffirmativeFollowUp(currentQuery)) {
            return null;
        }

        int offerIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> msg = history.get(i);
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            String content = msg.getOrDefault("content", "").trim();
            if (looksLikeAssistantOfferedFollowUp(content)) {
                offerIndex = i;
                break;
            }
        }

        if (offerIndex < 0) {
            return null;
        }

        String lastAssistant = history.get(offerIndex).getOrDefault("content", "").trim();
        String lastUser = null;
        for (int i = offerIndex - 1; i >= 0; i--) {
            Map<String, String> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                String content = msg.getOrDefault("content", "").trim();
                if (!content.isEmpty()) {
                    lastUser = content;
                    break;
                }
            }
        }

        StringBuilder rewritten = new StringBuilder("基于上一轮");
        if (lastUser != null && !lastUser.isBlank()) {
            rewritten.append("问题「").append(limit(lastUser, 80)).append("」");
        } else {
            rewritten.append("回答");
        }
        rewritten.append("，继续做进一步深入分析");

        String topics = extractOfferedTopics(lastAssistant);
        if (topics != null && !topics.isBlank()) {
            rewritten.append("，重点包括").append(limit(topics, 120));
        }
        rewritten.append("。");
        return rewritten.toString();
    }

    private boolean isAffirmativeFollowUp(String query) {
        if (query == null) {
            return false;
        }
        String normalized = query.trim().replaceAll("[\\s。！？!?,，、.]+", "");
        return List.of("需要", "我需要", "要", "我要", "继续", "请继续", "可以", "好的",
                "好", "是", "是的", "对", "对的", "进一步", "进一步分析", "详细点",
                "展开", "展开说说", "继续分析").contains(normalized);
    }

    private boolean looksLikeAssistantOfferedFollowUp(String assistant) {
        return assistant.contains("如果") && assistant.contains("需要")
                && (assistant.contains("进一步") || assistant.contains("继续") || assistant.contains("深入"));
    }

    private String extractOfferedTopics(String assistant) {
        int start = assistant.lastIndexOf("例如");
        if (start < 0) {
            return null;
        }
        start += "例如".length();
        int end = assistant.indexOf("）", start);
        if (end < 0) end = assistant.indexOf(")", start);
        if (end < 0) end = assistant.indexOf("，请", start);
        if (end < 0) end = assistant.indexOf("。", start);
        if (end <= start) {
            return null;
        }
        return assistant.substring(start, end).trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}

