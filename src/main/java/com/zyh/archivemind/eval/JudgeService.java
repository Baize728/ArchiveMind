package com.zyh.archivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.JudgeLlmClient;
import com.zyh.archivemind.eval.model.EvalSampleRow;
import com.zyh.archivemind.eval.model.JudgeResult;
import com.zyh.archivemind.eval.model.TraceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge 评测服务。
 *
 * judge(traceId, snapshot, evalSample) 流程：
 * 1. 根据 evalSample 是否含 expected_answer 选择 5 维 / 4 维 prompt
 * 2. 调用 JudgeLlmClient.chatSync 获取 LLM 评分 JSON
 * 3. 解析 JSON + clamp 到 [1,5]
 * 4. 失败返回 null
 */
@Service
public class JudgeService {

    private static final Logger logger = LoggerFactory.getLogger(JudgeService.class);

    /** 提取 LLM 回复中第一个 JSON 对象（处理 JSON 前后有多余文字的情况） */
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);

    private final JudgeLlmClient judgeLlmClient;
    private final ObjectMapper objectMapper;

    public JudgeService(JudgeLlmClient judgeLlmClient, ObjectMapper objectMapper) {
        this.judgeLlmClient = judgeLlmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单条 trace 执行 LLM Judge 评测。
     *
     * @param traceId    trace ID（仅用于日志）
     * @param snapshot   trace 快照（含 finalText、citedChunks 等）
     * @param evalSample 评测样本（含 expected_answer 等），可为 null
     * @return JudgeResult；失败返回 null
     */
    public JudgeResult judge(String traceId, TraceSnapshot snapshot, EvalSampleRow evalSample) {
        String prompt;
        boolean hasExpectedAnswer;
        try {
            hasExpectedAnswer = evalSample != null
                    && evalSample.getExpectedAnswer() != null
                    && !evalSample.getExpectedAnswer().isBlank();
            prompt = buildPrompt(snapshot, evalSample, hasExpectedAnswer);
        } catch (Exception e) {
            logger.error("构建 Judge prompt 失败 traceId={}: {}", traceId, e.getMessage(), e);
            return null;
        }

        String llmResponse = judgeLlmClient.chatSync(prompt);
        if (llmResponse == null || llmResponse.isBlank()) {
            logger.warn("Judge LLM 返回空 traceId={}", traceId);
            return null;
        }

        JudgeResult result = parseJudgeResult(llmResponse, hasExpectedAnswer, traceId);
        if (result == null) {
            logger.warn("Judge 结果解析失败 traceId={} raw={}", traceId, llmResponse);
        }
        return result;
    }

    // ── Prompt 构建 ──────────────────────────────────────────

    /**
     * 构建 Judge prompt。
     * 有 expected_answer → 5 维（faithfulness/relevance/completeness/naturalness/correctness）
     * 无 expected_answer → 4 维（faithfulness/relevance/completeness/naturalness）
     */
    String buildPrompt(TraceSnapshot snapshot, EvalSampleRow evalSample, boolean hasExpectedAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ArchiveMind 的回答质量评测 Judge。请对以下 AI 回答进行评分。\n\n");

        // 用户问题
        sb.append("【用户问题】\n");
        sb.append(snapshot.getUserInput() != null ? snapshot.getUserInput() : "(空)");
        sb.append("\n\n");

        // AI 回答
        sb.append("【AI 回答】\n");
        sb.append(snapshot.getFinalText() != null ? snapshot.getFinalText() : "(空)");
        sb.append("\n\n");

        // 检索引用的文档片段
        sb.append("【检索引用片段】\n");
        if (snapshot.getCitedChunks() != null && !snapshot.getCitedChunks().isEmpty()) {
            int idx = 1;
            for (var chunk : snapshot.getCitedChunks()) {
                sb.append(idx++).append(". ").append(chunk.getContent() != null ? chunk.getContent() : "");
                sb.append("\n");
            }
        } else {
            sb.append("(无检索结果)\n");
        }
        sb.append("\n");

        // 期望答案（如果有）
        if (hasExpectedAnswer) {
            sb.append("【期望答案（参考）】\n");
            sb.append(evalSample.getExpectedAnswer());
            sb.append("\n\n");
        }

        // 评分维度
        sb.append("【评分维度】每项 1-5 分（1=极差，5=完美）\n");
        sb.append("- faithfulness（忠实度）：AI 回答是否忠实于检索引用片段，有无编造\n");
        sb.append("- relevance（相关性）：AI 回答是否切题，与用户问题相关\n");
        sb.append("- completeness（完整性）：AI 回答是否完整覆盖了用户问题\n");
        sb.append("- naturalness（自然度）：AI 回答是否流畅自然、表达清晰\n");
        if (hasExpectedAnswer) {
            sb.append("- correctness（正确性）：AI 回答与期望答案对比，事实是否正确\n");
        }
        sb.append("\n");

        // 输出格式
        sb.append("请只输出 JSON，不要输出任何解释。格式：\n");
        if (hasExpectedAnswer) {
            sb.append("{\"faithfulness\":1,\"relevance\":1,\"completeness\":1,\"naturalness\":1,\"correctness\":1,\"reason\":\"简要说明\"}\n");
        } else {
            sb.append("{\"faithfulness\":1,\"relevance\":1,\"completeness\":1,\"naturalness\":1,\"reason\":\"简要说明\"}\n");
        }

        return sb.toString();
    }

    // ── 结果解析 ──────────────────────────────────────────────

    /**
     * 从 LLM 回复中解析 JudgeResult。
     * 处理 LLM 在 JSON 前后加文字的情况。
     */
    JudgeResult parseJudgeResult(String llmResponse, boolean hasExpectedAnswer, String traceId) {
        String json = extractJson(llmResponse);
        if (json == null) {
            logger.warn("无法从 Judge 回复中提取 JSON traceId={} raw={}", traceId, llmResponse);
            return null;
        }

        try {
            var node = objectMapper.readTree(json);

            int faithfulness = clamp(getInt(node, "faithfulness"), 1, 5);
            int relevance = clamp(getInt(node, "relevance"), 1, 5);
            int completeness = clamp(getInt(node, "completeness"), 1, 5);
            int naturalness = clamp(getInt(node, "naturalness"), 1, 5);

            Integer correctness = null;
            if (hasExpectedAnswer && node.has("correctness")) {
                correctness = clamp(getInt(node, "correctness"), 1, 5);
            }

            String reason = node.path("reason").asText("");

            return new JudgeResult(faithfulness, relevance, completeness, naturalness, correctness, reason);
        } catch (Exception e) {
            logger.error("解析 Judge JSON 失败 traceId={}: {}", traceId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 LLM 回复中提取第一个 JSON 对象。
     * 处理 LLM 在 JSON 前后加文字（如 "好的，以下是评分：{...}"）。
     */
    String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String trimmed = text.trim();

        // 快速路径：整个字符串就是 JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // 正则提取第一个 {...}
        Matcher matcher = JSON_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    private int getInt(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).asInt(1);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
