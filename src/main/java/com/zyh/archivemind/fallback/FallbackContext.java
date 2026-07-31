package com.zyh.archivemind.fallback;

import com.zyh.archivemind.clarify.SlotBundle;
import com.zyh.archivemind.intent.IntentResult;
import com.zyh.archivemind.model.SessionState;
import com.zyh.archivemind.trace.TraceScope;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 降级上下文（T1-5，Q12）。
 *
 * 大容器设计：各环节降级方法取自己需要的字段，接受参数膨胀代价换不丢语义信息。
 * 不同 stage 用不同字段组合：
 * - intent:   llmIntentResult + userInput + sessionState
 * - slot:     userInput
 * - clarify:  slots + missingSlots + clarifyTurn
 * - chitchat: （无业务字段）
 * - answer:   error + existingResponse
 */
@Data
@Builder
public class FallbackContext {

    private String stage;
    private TraceScope traceScope;

    // intent + slot 环节共用
    private IntentResult llmIntentResult;
    private String userInput;
    private SessionState sessionState;

    // clarify 环节
    private SlotBundle slots;
    private List<String> missingSlots;
    private int clarifyTurn;

    // answer 环节
    private Throwable error;
    private String existingResponse;
}
