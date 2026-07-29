package com.zyh.archivemind.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别编排入口（三层编排：LLM → 规则矫正 → 关键词兜底）。
 * 借鉴 Diet-Agent §8.1 的编排思路。
 *
 * 编排顺序：
 * 1. IntentAgentService.recognize（LLM 层）
 * 2. IntentReviseService.revise（规则矫正层，含关键词兜底）
 *
 * 与 Diet 的编排差异：Diet 把关键词兜底放在 IntentAgentService.fallback，
 * 规则矫正放在 IntentReviseService。ArchiveMind 把关键词匹配抽到
 * IntentReviseService 内部复用——避免关键词表两处维护。
 */
@Service
public class IntentRouter {

    private static final Logger logger = LoggerFactory.getLogger(IntentRouter.class);

    private final IntentAgentService intentAgentService;
    private final IntentReviseService intentReviseService;

    public IntentRouter(IntentAgentService intentAgentService,
                        IntentReviseService intentReviseService) {
        this.intentAgentService = intentAgentService;
        this.intentReviseService = intentReviseService;
    }

    /**
     * 三层编排：LLM 识别 → 规则矫正（含关键词兜底）。
     *
     * @param userMessage 用户当前输入
     * @param history     对话历史
     * @return 最终 IntentResult
     */
    public IntentResult route(String userMessage, List<Map<String, String>> history) {
        // 1. LLM 层
        IntentResult llmResult = intentAgentService.recognize(userMessage, history);

        // 2. 规则矫正层（LLM 返回 null 时内部兜底为 AMBIGUOUS）
        IntentResult revised = intentReviseService.revise(llmResult, userMessage);

        logger.info("意图识别结果: intent={}, confidence={}, source={}",
                revised.intent(), revised.confidence(), revised.source());
        return revised;
    }
}
