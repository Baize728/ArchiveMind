package com.zyh.archivemind.eval;

import com.zyh.archivemind.config.EvalProperties;
import com.zyh.archivemind.eval.mapper.EvalSampleMapper;
import com.zyh.archivemind.feedback.mapper.UserFeedbackMapper;
import com.zyh.archivemind.feedback.model.UserFeedbackRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feedback 分数计算测试
 *
 * 测试：
 * ① rating 优先：rating/5.0
 * ② action 映射：LIKE→1.0, DISLIKE→0.0, PARTIAL_CORRECT→0.5, OUTDATED→0.3
 * ③ 多条反馈取平均
 * ④ 无反馈返回 null
 */
@ExtendWith(MockitoExtension.class)
class FeedbackScoreTest {

    @Mock private JudgeService judgeService;
    @Mock private EvalSampleMapper evalSampleMapper;
    @Mock private UserFeedbackMapper userFeedbackMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    private EvaluationService evaluationService;
    private Method feedbackScoreMethod;

    @BeforeEach
    void setUp() throws Exception {
        EvalProperties evalProperties = new EvalProperties();
        evaluationService = new EvaluationService(
                judgeService, evalSampleMapper, userFeedbackMapper,
                evalProperties, jdbcTemplate, new ObjectMapper()
        );

        feedbackScoreMethod = EvaluationService.class.getDeclaredMethod(
                "feedbackScore", List.class);
        feedbackScoreMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Double callFeedbackScore(List<UserFeedbackRow> feedbacks) throws Exception {
        return (Double) feedbackScoreMethod.invoke(evaluationService, feedbacks);
    }

    private UserFeedbackRow row(String action, Integer rating) {
        UserFeedbackRow r = new UserFeedbackRow();
        r.setAction(action);
        r.setRating(rating);
        return r;
    }

    @Test
    @DisplayName("① rating 优先：rating=4 → 4/5 = 0.8")
    void testRatingPriority() throws Exception {
        Double result = callFeedbackScore(List.of(row("DISLIKE", 4)));
        assertEquals(0.8, result, 0.0001);
    }

    @Test
    @DisplayName("② action 映射：LIKE → 1.0")
    void testActionLike() throws Exception {
        Double result = callFeedbackScore(List.of(row("LIKE", null)));
        assertEquals(1.0, result, 0.0001);
    }

    @Test
    @DisplayName("② action 映射：DISLIKE → 0.0")
    void testActionDislike() throws Exception {
        Double result = callFeedbackScore(List.of(row("DISLIKE", null)));
        assertEquals(0.0, result, 0.0001);
    }

    @Test
    @DisplayName("② action 映射：PARTIAL_CORRECT → 0.5")
    void testActionPartialCorrect() throws Exception {
        Double result = callFeedbackScore(List.of(row("PARTIAL_CORRECT", null)));
        assertEquals(0.5, result, 0.0001);
    }

    @Test
    @DisplayName("② action 映射：OUTDATED → 0.3")
    void testActionOutdated() throws Exception {
        Double result = callFeedbackScore(List.of(row("OUTDATED", null)));
        assertEquals(0.3, result, 0.0001);
    }

    @Test
    @DisplayName("③ 多条反馈取平均：LIKE(1.0) + DISLIKE(0.0) → 0.5")
    void testMultipleFeedbacksAverage() throws Exception {
        Double result = callFeedbackScore(List.of(
                row("LIKE", null),
                row("DISLIKE", null)
        ));
        assertEquals(0.5, result, 0.0001);
    }

    @Test
    @DisplayName("④ 无反馈返回 null")
    void testEmptyFeedbacks() throws Exception {
        Double result = callFeedbackScore(List.of());
        assertNull(result);
    }

    @Test
    @DisplayName("rating 边界：rating=5 → 1.0，rating=0 → 0.0")
    void testRatingBoundary() throws Exception {
        assertEquals(1.0, callFeedbackScore(List.of(row("LIKE", 5))), 0.0001);
        assertEquals(0.0, callFeedbackScore(List.of(row("DISLIKE", 0))), 0.0001);
    }
}
