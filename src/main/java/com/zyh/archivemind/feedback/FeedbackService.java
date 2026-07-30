package com.zyh.archivemind.feedback;

import com.zyh.archivemind.feedback.mapper.UserFeedbackMapper;
import com.zyh.archivemind.feedback.model.BadCaseFeedbackMessage;
import com.zyh.archivemind.feedback.model.FeedbackRequest;
import com.zyh.archivemind.feedback.model.UserFeedbackRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 用户反馈采集服务。
 *
 * save 流程：
 * 1. 同步写入 user_feedback 表（保证用户反馈不丢）
 * 2. 若为负向反馈，异步发送 Kafka 消息到 eval-badcase-feedback 主题（触发 badcase 回流）
 */
@Service
public class FeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackService.class);

    /** 判定为负向反馈的 action 集合 */
    private static final Set<String> NEGATIVE_ACTIONS = Set.of("DISLIKE", "PARTIAL_CORRECT", "OUTDATED");

    private final UserFeedbackMapper userFeedbackMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String badcaseTopic;

    public FeedbackService(UserFeedbackMapper userFeedbackMapper,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           @Value("${spring.kafka.topic.eval-badcase-feedback}") String badcaseTopic) {
        this.userFeedbackMapper = userFeedbackMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.badcaseTopic = badcaseTopic;
    }

    /**
     * 判断是否为负向反馈。
     */
    public boolean isNegativeFeedback(String action) {
        return action != null && NEGATIVE_ACTIONS.contains(action.toUpperCase());
    }

    /**
     * 保存用户反馈：同步落库 + 负向反馈异步发 Kafka。
     *
     * @Transactional 确保 Kafka 事务模板能正常工作（KafkaConfig 配置了 transactional-id-prefix）。
     *
     * @param userId  当前登录用户 ID
     * @param request 前端提交的反馈请求
     */
    @Transactional
    public void save(String userId, FeedbackRequest request) {
        // 1. 同步写入 user_feedback 表
        UserFeedbackRow row = new UserFeedbackRow();
        row.setUserId(userId);
        row.setConversationId(request.getConversationId());
        row.setTraceId(request.getTraceId());
        row.setAction(request.getAction());
        row.setRating(request.getRating());
        row.setComment(request.getComment());
        userFeedbackMapper.insert(row);
        logger.info("用户反馈已落库 userId={} traceId={} action={}", userId, request.getTraceId(), request.getAction());

        // 2. 负向反馈 → 异步发 Kafka（不阻塞主流程，发送失败仅告警）
        if (isNegativeFeedback(request.getAction())) {
            BadCaseFeedbackMessage msg = new BadCaseFeedbackMessage(
                    request.getTraceId(),
                    request.getAction(),
                    request.getComment());
            try {
                kafkaTemplate.send(badcaseTopic, request.getTraceId(), msg);
                logger.info("负向反馈已发送 Kafka topic={} traceId={}", badcaseTopic, request.getTraceId());
            } catch (Exception e) {
                logger.error("负向反馈发送 Kafka 失败 traceId={}: {}", request.getTraceId(), e.getMessage(), e);
            }
        }
    }
}
