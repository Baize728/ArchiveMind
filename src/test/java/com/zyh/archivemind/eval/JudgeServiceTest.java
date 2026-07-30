package com.zyh.archivemind.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.Llm.JudgeLlmClient;
import com.zyh.archivemind.eval.model.CitedChunk;
import com.zyh.archivemind.eval.model.EvalSampleRow;
import com.zyh.archivemind.eval.model.JudgeResult;
import com.zyh.archivemind.eval.model.TraceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * JudgeService 测试
 *
 * 测试：
 * ① 4 维 JSON 解析
 * ② 5 维 JSON 解析（含 correctness）
 * ③ LLM 返回非 JSON 时返回 null
 * ④ LLM 返回 null 时返回 null
 * ⑤ prompt 构造：有/无 expected_answer 分支
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceTest {

    @Mock private JudgeLlmClient judgeLlmClient;

    private JudgeService judgeService;
    private Method parseJudgeResultMethod;

    @BeforeEach
    void setUp() throws Exception {
        judgeService = new JudgeService(judgeLlmClient, new ObjectMapper());
        parseJudgeResultMethod = JudgeService.class.getDeclaredMethod(
                "parseJudgeResult", String.class, boolean.class, String.class);
        parseJudgeResultMethod.setAccessible(true);
    }

    private JudgeResult callParseJudgeResult(String reply) throws Exception {
        return (JudgeResult) parseJudgeResultMethod.invoke(judgeService, reply, false, "trace-test");
    }

    private JudgeResult callParseJudgeResultWithExpected(String reply) throws Exception {
        return (JudgeResult) parseJudgeResultMethod.invoke(judgeService, reply, true, "trace-test");
    }

    @Test
    @DisplayName("① 4 维 JSON 解析")
    void testParseFourDim() throws Exception {
        String json = """
                {"faithfulness": 4, "relevance": 5, "completeness": 3, "naturalness": 4, "reason": "good"}""";
        JudgeResult result = callParseJudgeResult(json);
        assertNotNull(result);
        assertEquals(4, result.getFaithfulness());
        assertEquals(5, result.getRelevance());
        assertEquals(3, result.getCompleteness());
        assertEquals(4, result.getNaturalness());
        assertNull(result.getCorrectness());
        assertEquals("good", result.getReason());
    }

    @Test
    @DisplayName("② 5 维 JSON 解析（含 correctness）")
    void testParseFiveDim() throws Exception {
        String json = """
                {"faithfulness": 5, "relevance": 4, "completeness": 4, "naturalness": 5, "correctness": 3, "reason": "ok"}""";
        JudgeResult result = callParseJudgeResultWithExpected(json);
        assertNotNull(result);
        assertEquals(5, result.getFaithfulness());
        assertEquals(4, result.getRelevance());
        assertEquals(4, result.getCompleteness());
        assertEquals(5, result.getNaturalness());
        assertEquals(3, result.getCorrectness());
    }

    @Test
    @DisplayName("③ LLM 返回非 JSON 时返回 null")
    void testParseNonJson() throws Exception {
        JudgeResult result = callParseJudgeResult("I cannot evaluate this.");
        assertNull(result);
    }

    @Test
    @DisplayName("④ LLM 返回 null 时 judge() 返回 null")
    void testJudgeNullReply() {
        when(judgeLlmClient.chatSync(anyString())).thenReturn(null);

        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setUserInput("test");
        snapshot.setFinalText("answer");

        JudgeResult result = judgeService.judge("trace-1", snapshot, null);
        assertNull(result);
    }

    @Test
    @DisplayName("⑤ 完整流程：LLM 返回 4 维 JSON → JudgeResult 正确解析")
    void testFullFlowFourDim() {
        String llmReply = """
                {"faithfulness": 5, "relevance": 4, "completeness": 4, "naturalness": 5, "reason": "faithful answer"}""";
        when(judgeLlmClient.chatSync(anyString())).thenReturn(llmReply);

        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setUserInput("什么是年假？");
        snapshot.setFinalText("年假是带薪休假。");
        snapshot.setCitedChunks(List.of(new CitedChunk(1, "md5", "年假规定...", 0.9)));

        JudgeResult result = judgeService.judge("trace-1", snapshot, null);
        assertNotNull(result);
        assertEquals(5, result.getFaithfulness());
        assertEquals(4, result.getRelevance());
        assertNull(result.getCorrectness());
    }

    @Test
    @DisplayName("⑤ 完整流程：有 expected_answer → 5 维 JSON 解析")
    void testFullFlowFiveDim() {
        String llmReply = """
                {"faithfulness": 5, "relevance": 5, "completeness": 4, "naturalness": 4, "correctness": 5, "reason": "matches expected"}""";
        when(judgeLlmClient.chatSync(anyString())).thenReturn(llmReply);

        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setUserInput("年假多少天？");
        snapshot.setFinalText("15天。");

        EvalSampleRow sample = new EvalSampleRow();
        sample.setExpectedAnswer("15天");

        JudgeResult result = judgeService.judge("trace-1", snapshot, sample);
        assertNotNull(result);
        assertEquals(5, result.getCorrectness());
    }

    @Test
    @DisplayName("LLM 在 JSON 前后加文字时正确提取")
    void testExtractJsonFromText() throws Exception {
        String reply = "好的，以下是评分结果：\n" +
                "{\"faithfulness\": 4, \"relevance\": 4, \"completeness\": 3, \"naturalness\": 4, \"reason\": \"ok\"}\n" +
                "希望有帮助。";
        JudgeResult result = callParseJudgeResult(reply);
        assertNotNull(result);
        assertEquals(4, result.getFaithfulness());
    }

    @Test
    @DisplayName("分数 clamp 到 [1, 5]")
    void testScoreClamp() throws Exception {
        String json = """
                {"faithfulness": 10, "relevance": 0, "completeness": 3, "naturalness": -1, "reason": "clamp test"}""";
        JudgeResult result = callParseJudgeResult(json);
        assertNotNull(result);
        assertEquals(5, result.getFaithfulness());  // 10 → 5
        assertEquals(1, result.getRelevance());      // 0 → 1
        assertEquals(3, result.getCompleteness());   // 3 → 3
        assertEquals(1, result.getNaturalness());    // -1 → 1
    }
}
