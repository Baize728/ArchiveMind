package com.zyh.archivemind.intent;

import com.zyh.archivemind.fallback.FallbackContext;
import com.zyh.archivemind.fallback.FallbackPolicyService;
import com.zyh.archivemind.model.SessionState;
import com.zyh.archivemind.trace.TraceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别编排入口（三层编排：LLM → 降级策略统管）。
 *
 * T1-5 改动（Q12）：IntentReviseService 删除，降级逻辑迁入 FallbackPolicyService。
 * route 构造 FallbackContext 调 fallbackPolicyService.execute("intent", ctx)。
 */
@Service
public class IntentRouter {

    private static final Logger logger = LoggerFactory.getLogger(IntentRouter.class);

    private final IntentAgentService intentAgentService;
    private final FallbackPolicyService fallbackPolicyService;

    public IntentRouter(IntentAgentService intentAgentService,
                        FallbackPolicyService fallbackPolicyService) {
        this.intentAgentService = intentAgentService;
        this.fallbackPolicyService = fallbackPolicyService;
    }

    /**
     * 三层编排：LLM 识别 → 降级策略统管（规则矫正 + 关键词兜底 + Q11 升格）。
     *
     * @param userMessage 用户当前输入（T1-2 起为改写后的 query）
     * @param history     对话历史
     * @param state       会话状态（供升格判定读 lastIntent；可为 null）
     * @param traceScope  Trace 作用域（可为 null）
     * @return 最终 IntentResult
     */
    public IntentResult route(String userMessage, List<Map<String, String>> history,
                              SessionState state, TraceScope traceScope) {
        // 1. LLM 层
        IntentResult llmResult = intentAgentService.recognize(userMessage, history);

        // 2. 降级策略统管（规则矫正 + 关键词兜底 + Q11 升格）
        FallbackContext ctx = FallbackContext.builder()
                .stage("intent")
                .traceScope(traceScope)
                .llmIntentResult(llmResult)
                .userInput(userMessage)
                .sessionState(state)
                .build();
        IntentResult revised = (IntentResult) fallbackPolicyService.execute("intent", ctx);

        logger.info("意图识别结果: intent={}, confidence={}, source={}",
                revised.intent(), revised.confidence(), revised.source());
        return revised;
    }
}
