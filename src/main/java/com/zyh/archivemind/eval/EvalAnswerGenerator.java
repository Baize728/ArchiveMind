package com.zyh.archivemind.eval;

import com.zyh.archivemind.Llm.GenerationParams;
import com.zyh.archivemind.Llm.LlmMessage;
import com.zyh.archivemind.Llm.LlmProvider;
import com.zyh.archivemind.Llm.LlmRequest;
import com.zyh.archivemind.Llm.LlmRouter;
import com.zyh.archivemind.Llm.LlmStreamCallback;
import com.zyh.archivemind.Tool.ToolCall;
import com.zyh.archivemind.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EvalAnswerGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EvalAnswerGenerator.class);

    private final LlmRouter llmRouter;
    private final EvalProperties properties;

    public EvalAnswerGenerator(LlmRouter llmRouter, EvalProperties properties) {
        this.llmRouter = llmRouter;
        this.properties = properties;
    }

    public String generate(EvalCase evalCase, List<SearchResult> retrieved) {
        List<SearchResult> contexts = retrieved == null
                ? List.of()
                : retrieved.stream().limit(properties.getAnswerTopK()).toList();
        String userPrompt = buildPrompt(evalCase, contexts);
        LlmProvider provider = llmRouter.getDefaultProvider();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> answerRef = new AtomicReference<>("");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.system("你是 ArchiveMind 离线评测回答器。只能依据给定参考片段回答；如果片段不足，请回答\"暂无相关信息\"并说明原因。回答必须使用简体中文。列表题必须先完整合并所有参考片段中的枚举项，不要因为某项出现在靠后的片段就遗漏。"),
                        LlmMessage.user(userPrompt)
                ))
                .params(GenerationParams.builder()
                        .temperature(0.0)
                        .maxTokens(1200)
                        .topP(1.0)
                        .build())
                .build();

        provider.streamChat(request, new LlmStreamCallback() {
            @Override
            public void onTextChunk(String chunk) {
                answerRef.updateAndGet(value -> value + chunk);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                logger.debug("离线评测回答忽略工具调用: {}", toolCall.functionName());
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(properties.getAnswerTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                throw new RuntimeException("评测回答生成超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("评测回答生成被中断", e);
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("评测回答生成失败", errorRef.get());
        }
        return answerRef.get();
    }

    private String buildPrompt(EvalCase evalCase, List<SearchResult> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(evalCase.question()).append("\n\n");
        sb.append("参考片段：\n");
        int totalChars = 0;
        for (int i = 0; i < contexts.size(); i++) {
            SearchResult result = contexts.get(i);
            String text = result.getTextContent() == null ? "" : result.getTextContent();
            int remaining = properties.getMaxContextChars() - totalChars;
            if (remaining <= 0) {
                break;
            }
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
            }
            totalChars += text.length();
            sb.append("[").append(i + 1).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                sb.append(result.getFileName()).append(" ");
            }
            if (result.getHeadingPath() != null && !result.getHeadingPath().isBlank()) {
                sb.append(result.getHeadingPath()).append(" ");
            }
            sb.append("\n").append(text).append("\n\n");
        }
        sb.append("要求：先给结论，再给依据；如引用片段，请使用 [N]；不得编造片段外信息。");
        sb.append("如果问题是在问“有哪些/支持哪些/包括哪些”，请先通读全部参考片段，合并所有明确出现的条目后再回答。");
        return sb.toString();
    }
}
