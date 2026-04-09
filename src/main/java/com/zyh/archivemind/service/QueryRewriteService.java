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
}
