package com.zyh.archivemind.clarify;

import com.zyh.archivemind.client.ClarifyLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 澄清 Agent 服务（T1-2，Q7）。
 *
 * 根据缺失槽位生成追问文案。
 * LLM 失败/超时 → 模板兜底（ClarifyRuleService.fallbackQuestion）。
 * 同步返回（Q7）。
 *
 * 第 2 轮追问更具体（Q6）：withOptions=true 时 prompt 要求 LLM 给出候选选项。
 */
@Service
public class ClarifyAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ClarifyAgentService.class);

    private final ClarifyLlmClient clarifyLlmClient;
    private final ClarifyRuleService clarifyRuleService;

    public ClarifyAgentService(ClarifyLlmClient clarifyLlmClient, ClarifyRuleService clarifyRuleService) {
        this.clarifyLlmClient = clarifyLlmClient;
        this.clarifyRuleService = clarifyRuleService;
    }

    /**
     * 根据缺失槽位生成追问文案。
     *
     * @param userInput    用户原始输入
     * @param slots        当前槽位
     * @param missingSlots 缺失的槽位列表
     * @param clarifyTurn  当前澄清轮次（>=1 时 withOptions=true）
     * @return ClarifyResult.ask(...)
     */
    public ClarifyResult decide(String userInput, SlotBundle slots,
                                 List<String> missingSlots, int clarifyTurn) {
        boolean withOptions = clarifyTurn >= 1;

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", buildSystemPrompt(withOptions)));
            messages.add(Map.of("role", "user", "content",
                    "用户原话：" + userInput + "\n已知信息：" + slots + "\n缺失字段：" + missingSlots
                            + "\n请生成一句简短的自然追问，引导用户补充缺失信息。"
                            + (withOptions ? "请给出候选选项（如\"是A还是B？\"）。" : "")));

            String question = clarifyLlmClient.chatSync(messages, 128, 0.7);
            if (question == null || question.isBlank()) {
                question = clarifyRuleService.fallbackQuestion(missingSlots, withOptions);
            }
            return ClarifyResult.ask(question, missingSlots);
        } catch (Exception e) {
            logger.warn("ClarifyAgentService LLM 生成追问失败，走模板兜底: {}", e.getMessage());
            return ClarifyResult.ask(
                    clarifyRuleService.fallbackQuestion(missingSlots, withOptions), missingSlots);
        }
    }

    private String buildSystemPrompt(boolean withOptions) {
        String base = "你是ArchiveMind知识库的澄清追问模块。用户的问题缺少必要的检索约束，"
                + "请生成一句简短友好的追问，引导用户补充信息。"
                + "只输出追问文案，不要解释，不要寒暄。";
        if (withOptions) {
            base += "请给出候选选项帮助用户快速选择（如\"请问是A还是B？\"）。";
        }
        return base;
    }
}
