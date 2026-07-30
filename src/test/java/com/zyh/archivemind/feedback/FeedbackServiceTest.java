package com.zyh.archivemind.feedback;

import com.zyh.archivemind.feedback.mapper.UserFeedbackMapper;
import com.zyh.archivemind.feedback.model.BadCaseFeedbackMessage;
import com.zyh.archivemind.feedback.model.FeedbackRequest;
import com.zyh.archivemind.feedback.model.UserFeedbackRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FeedbackService 测试
 *
 * 测试：
 * ① 正向反馈（LIKE）不触发 Kafka 发送
 * ② 负向反馈（DISLIKE）触发 Kafka 发送
 * ③ PARTIAL_CORRECT 触发 Kafka
 * ④ OUTDATED 触发 Kafka
 * ⑤ Kafka 发送失败不影响 user_feedback 写入
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock private UserFeedbackMapper feedbackMapper;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private FeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(feedbackMapper, kafkaTemplate, "eval-badcase-feedback");
    }

    private FeedbackRequest request(String action) {
        FeedbackRequest req = new FeedbackRequest();
        req.setConversationId("conv-1");
        req.setTraceId("trace-1");
        req.setAction(action);
        return req;
    }

    @Test
    @DisplayName("① LIKE 不触发 Kafka 发送")
    void testLikeNoKafka() {
        feedbackService.save("user-1", request("LIKE"));

        verify(feedbackMapper).insert(any(UserFeedbackRow.class));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("② DISLIKE 触发 Kafka 发送")
    void testDislikeTriggersKafka() {
        feedbackService.save("user-1", request("DISLIKE"));

        verify(feedbackMapper).insert(any(UserFeedbackRow.class));
        verify(kafkaTemplate).send(eq("eval-badcase-feedback"), eq("trace-1"), any(BadCaseFeedbackMessage.class));
    }

    @Test
    @DisplayName("③ PARTIAL_CORRECT 触发 Kafka 发送")
    void testPartialCorrectTriggersKafka() {
        feedbackService.save("user-1", request("PARTIAL_CORRECT"));

        verify(kafkaTemplate).send(eq("eval-badcase-feedback"), eq("trace-1"), any(BadCaseFeedbackMessage.class));
    }

    @Test
    @DisplayName("④ OUTDATED 触发 Kafka 发送")
    void testOutdatedTriggersKafka() {
        feedbackService.save("user-1", request("OUTDATED"));

        verify(kafkaTemplate).send(eq("eval-badcase-feedback"), eq("trace-1"), any(BadCaseFeedbackMessage.class));
    }

    @Test
    @DisplayName("⑤ Kafka 发送失败不影响 user_feedback 写入")
    void testKafkaFailureDoesNotAffectInsert() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        // 不应该抛异常
        assertDoesNotThrow(() -> feedbackService.save("user-1", request("DISLIKE")));

        // user_feedback 仍被写入
        verify(feedbackMapper).insert(any(UserFeedbackRow.class));
    }

    @Test
    @DisplayName("Kafka 消息内容正确")
    void testKafkaMessageContent() {
        FeedbackRequest req = request("DISLIKE");
        req.setComment("答案完全错误");

        feedbackService.save("user-1", req);

        ArgumentCaptor<BadCaseFeedbackMessage> captor = ArgumentCaptor.forClass(BadCaseFeedbackMessage.class);
        verify(kafkaTemplate).send(eq("eval-badcase-feedback"), eq("trace-1"), captor.capture());

        BadCaseFeedbackMessage msg = captor.getValue();
        assertEquals("trace-1", msg.getTraceId());
        assertEquals("DISLIKE", msg.getAction());
        assertEquals("答案完全错误", msg.getComment());
    }
}
