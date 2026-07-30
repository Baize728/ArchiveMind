package com.zyh.archivemind.eval;

import com.zyh.archivemind.config.EvalProperties;
import com.zyh.archivemind.eval.mapper.EvalSampleMapper;
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
 * V1 验收：总分公式 + 动态归一
 *
 * 测试场景：
 * ① 三维度齐全：rule×0.5 + judge×0.3 + feedback×0.2
 * ② 缺 judge：rule + feedback 归一
 * ③ 缺 feedback：rule + judge 归一
 * ④ 全缺：返回 null
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceWeightedScoreTest {

    @Mock private JudgeService judgeService;
    @Mock private EvalSampleMapper evalSampleMapper;
    @Mock private UserFeedbackMapper userFeedbackMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    private EvalProperties evalProperties;
    private EvaluationService evaluationService;
    private Method weightedScoreMethod;

    @BeforeEach
    void setUp() throws Exception {
        evalProperties = new EvalProperties();
        // 使用默认权重 0.5/0.3/0.2

        evaluationService = new EvaluationService(
                judgeService, evalSampleMapper, userFeedbackMapper,
                evalProperties, jdbcTemplate, new ObjectMapper()
        );

        // 用反射访问 private 方法
        weightedScoreMethod = EvaluationService.class.getDeclaredMethod(
                "weightedScore", Double.class, Double.class, Double.class);
        weightedScoreMethod.setAccessible(true);
    }

    private Double callWeightedScore(Double rule, Double judge, Double feedback) throws Exception {
        return (Double) weightedScoreMethod.invoke(evaluationService, rule, judge, feedback);
    }

    @Test
    @DisplayName("① 三维度齐全：rule×0.5 + judge×0.3 + feedback×0.2")
    void testAllPresent() throws Exception {
        Double result = callWeightedScore(0.8, 0.9, 1.0);
        // 0.8*0.5 + 0.9*0.3 + 1.0*0.2 = 0.4 + 0.27 + 0.2 = 0.87
        assertEquals(0.87, result, 0.0001);
    }

    @Test
    @DisplayName("② 缺 judge：rule + feedback 归一 (0.5r + 0.2f) / 0.7")
    void testMissingJudge() throws Exception {
        Double result = callWeightedScore(0.8, null, 1.0);
        // (0.8*0.5 + 1.0*0.2) / (0.5 + 0.2) = (0.4 + 0.2) / 0.7 = 0.6/0.7 ≈ 0.8571
        assertEquals(0.6 / 0.7, result, 0.0001);
    }

    @Test
    @DisplayName("③ 缺 feedback：rule + judge 归一 (0.5r + 0.3j) / 0.8")
    void testMissingFeedback() throws Exception {
        Double result = callWeightedScore(0.8, 0.9, null);
        // (0.8*0.5 + 0.9*0.3) / (0.5 + 0.3) = (0.4 + 0.27) / 0.8 = 0.67/0.8 = 0.8375
        assertEquals(0.67 / 0.8, result, 0.0001);
    }

    @Test
    @DisplayName("④ 全缺：返回 null")
    void testAllNull() throws Exception {
        Double result = callWeightedScore(null, null, null);
        assertNull(result);
    }

    @Test
    @DisplayName("仅 rule 存在时返回 rule 本身")
    void testOnlyRule() throws Exception {
        Double result = callWeightedScore(0.8, null, null);
        // (0.8*0.5) / 0.5 = 0.8
        assertEquals(0.8, result, 0.0001);
    }
}
