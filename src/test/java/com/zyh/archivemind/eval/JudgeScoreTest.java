package com.zyh.archivemind.eval;

import com.zyh.archivemind.config.EvalProperties;
import com.zyh.archivemind.eval.mapper.EvalSampleMapper;
import com.zyh.archivemind.eval.model.JudgeResult;
import com.zyh.archivemind.feedback.mapper.UserFeedbackMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V4 验收：Judge 4 维评分 + hallucinationControl 衍生
 *
 * 测试：
 * ① 4 维分数解析 + judgeScore 加权聚合 f×0.4+r×0.3+c×0.2+n×0.1
 * ② 5 维（含 correctness）聚合
 * ③ hallucinationControl = faithfulness >= 3 ? 1 : 0
 */
@ExtendWith(MockitoExtension.class)
class JudgeScoreTest {

    @Mock private JudgeService judgeService;
    @Mock private EvalSampleMapper evalSampleMapper;
    @Mock private UserFeedbackMapper userFeedbackMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    private EvalProperties evalProperties;
    private EvaluationService evaluationService;
    private Method aggregateJudgeScoreMethod;

    @BeforeEach
    void setUp() throws Exception {
        evalProperties = new EvalProperties();

        evaluationService = new EvaluationService(
                judgeService, evalSampleMapper, userFeedbackMapper,
                evalProperties, jdbcTemplate, new ObjectMapper()
        );

        aggregateJudgeScoreMethod = EvaluationService.class.getDeclaredMethod(
                "aggregateJudgeScore", JudgeResult.class);
        aggregateJudgeScoreMethod.setAccessible(true);
    }

    private Double callAggregateJudgeScore(JudgeResult judge) throws Exception {
        return (Double) aggregateJudgeScoreMethod.invoke(evaluationService, judge);
    }

    @Test
    @DisplayName("① 4 维聚合：faithfulness=5, relevance=4, completeness=4, naturalness=5 → 0.9")
    void testFourDimAggregation() throws Exception {
        JudgeResult judge = new JudgeResult(5, 4, 4, 5, null, "good");
        // f=5/5=1.0, r=4/5=0.8, c=4/5=0.8, n=5/5=1.0
        // 1.0*0.4 + 0.8*0.3 + 0.8*0.2 + 1.0*0.1 = 0.4+0.24+0.16+0.1 = 0.9
        Double result = callAggregateJudgeScore(judge);
        assertEquals(0.9, result, 0.0001);
    }

    @Test
    @DisplayName("② 5 维聚合（含 correctness=4）：f×0.35+r×0.25+c×0.15+corr×0.15+n×0.10")
    void testFiveDimAggregation() throws Exception {
        JudgeResult judge = new JudgeResult(5, 5, 4, 5, 4, "good with expected answer");
        // f=1.0, r=1.0, c=0.8, n=1.0, corr=0.8
        // 1.0*0.35 + 1.0*0.25 + 0.8*0.15 + 0.8*0.15 + 1.0*0.10
        // = 0.35 + 0.25 + 0.12 + 0.12 + 0.10 = 0.94
        Double result = callAggregateJudgeScore(judge);
        assertEquals(0.94, result, 0.0001);
    }

    @Test
    @DisplayName("③ hallucinationControl：faithfulness >= 3 → 1.0（无幻觉）")
    void testHallucinationControlPass() {
        // faithfulness=4 >= 3 → hallucinationControl = 1.0
        JudgeResult judge = new JudgeResult(4, 4, 4, 4, null, "ok");
        assertTrue(judge.getFaithfulness() >= 3);
    }

    @Test
    @DisplayName("③ hallucinationControl：faithfulness < 3 → 0.0（有幻觉）")
    void testHallucinationControlFail() {
        // faithfulness=2 < 3 → hallucinationControl = 0.0
        JudgeResult judge = new JudgeResult(2, 4, 4, 4, null, "hallucination detected");
        assertFalse(judge.getFaithfulness() >= 3);
    }

    @Test
    @DisplayName("边界：faithfulness=3 → hallucinationControl = 1.0（恰好通过）")
    void testHallucinationControlBoundary() {
        JudgeResult judge = new JudgeResult(3, 4, 4, 4, null, "borderline");
        assertTrue(judge.getFaithfulness() >= 3);
    }
}
