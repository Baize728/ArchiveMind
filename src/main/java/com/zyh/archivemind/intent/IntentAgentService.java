package com.zyh.archivemind.intent;

import com.zyh.archivemind.client.IntentLlmClient;
import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.util.LlmJsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 意图识别 LLM 层（第 1 层）。
 * 借鉴 Diet-Agent §8.1 IntentAgentService.recognize。
 *
 * 调用轻量 LLM 识别意图，约束输出 JSON {intent, confidence}。
 * 失败/超时/解析失败返回 null，交由 IntentReviseService 兜底。
 * 与 Diet 的差异：不抽取槽位（槽位归 T1-2 澄清层）。
 */
@Service
public class IntentAgentService {

    private static final Logger logger = LoggerFactory.getLogger(IntentAgentService.class);

    private final IntentLlmClient intentLlmClient;
    private final AiProperties aiProperties;

    public IntentAgentService(IntentLlmClient intentLlmClient, AiProperties aiProperties) {
        this.intentLlmClient = intentLlmClient;
        this.aiProperties = aiProperties;
    }

    /**
     * 调用轻量 LLM 识别意图。
     *
     * @param userMessage 用户当前输入
     * @param history     对话历史（role/content 的 Map 列表）
     * @return IntentResult(source="LLM")；LLM 失败/超时/解析失败返回 null
     */
    public IntentResult recognize(String userMessage, List<Map<String, String>> history) {
        AiProperties.Intent cfg = aiProperties.getIntent();
        if (!cfg.isEnabled()) {
            return null;
        }

        try {
            List<Map<String, String>> messages = buildPrompt(userMessage, history, cfg);
            String reply = intentLlmClient.chatSync(messages);
            if (reply == null || reply.isBlank()) {
                logger.warn("意图识别 LLM 返回空结果");
                return null;
            }
            return parseResult(reply);
        } catch (Exception e) {
            logger.warn("意图识别 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 构造传给 LLM 的消息列表（system prompt + user prompt） */
    private List<Map<String, String>> buildPrompt(String userMessage,
                                                   List<Map<String, String>> history,
                                                   AiProperties.Intent cfg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", cfg.getSystemPrompt()));

        // 拼接最近几轮历史供 LLM 理解上下文（最多取最近 6 条）
        if (history != null && !history.isEmpty()) {
            int maxMsgs = Math.min(history.size(), 6);
            List<Map<String, String>> recent = history.subList(history.size() - maxMsgs, history.size());
            for (Map<String, String> msg : recent) {
                String role = msg.get("role");
                String content = msg.getOrDefault("content", "");
                if ("user".equals(role) || "assistant".equals(role)) {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
        }

        messages.add(Map.of("role", "user", "content",
                "用户输入：" + userMessage + "\n请输出 JSON，字段为 intent 和 confidence。"));
        return messages;
    }

    /** 将 LLM 返回的 JSON 文本解析为 IntentResult */
    private IntentResult parseResult(String reply) {
        JsonNode root = LlmJsonExtractor.parseObject(reply);
        if (root == null) {
            return null;
        }

        String intentStr = root.path("intent").asText(null);
        if (intentStr == null || intentStr.isBlank()) {
            logger.warn("意图识别 JSON 缺少 intent 字段: {}", reply);
            return null;
        }

        Intent intent;
        try {
            intent = Intent.valueOf(intentStr.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("意图识别返回非法枚举值: {}", intentStr);
            return null;
        }

        double confidence = root.path("confidence").asDouble(0.5);
        return new IntentResult(intent, confidence, "LLM", reply);
    }
}
