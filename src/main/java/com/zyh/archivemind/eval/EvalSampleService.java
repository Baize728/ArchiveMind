package com.zyh.archivemind.eval;

import com.zyh.archivemind.eval.mapper.EvalSampleMapper;
import com.zyh.archivemind.eval.model.EvalSampleRow;
import com.zyh.archivemind.feedback.model.BadCaseFeedbackMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * eval_samples CRUD + BadCase 回流 Kafka 消费。
 *
 * 不做评测逻辑（归 EvaluationService），只负责标注数据管理。
 */
@Service
public class EvalSampleService {

    private static final Logger logger = LoggerFactory.getLogger(EvalSampleService.class);

    private final EvalSampleMapper evalSampleMapper;

    public EvalSampleService(EvalSampleMapper evalSampleMapper) {
        this.evalSampleMapper = evalSampleMapper;
    }

    /**
     * 消费 BadCase 反馈消息，插入 eval_samples（source=BADCASE_BACKFLOW）。
     * trace_id 有 UNIQUE KEY，重复消息会抛 DuplicateKeyException，忽略即可。
     */
    @KafkaListener(topics = "${spring.kafka.topic.eval-badcase-feedback}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeBadCaseFeedback(BadCaseFeedbackMessage message) {
        try {
            evalSampleMapper.insertBadCase(
                    message.getTraceId(),
                    "BADCASE_BACKFLOW",
                    message.getAction(),
                    message.getComment());
            logger.info("BadCase 回流已插入 eval_samples traceId={}", message.getTraceId());
        } catch (DuplicateKeyException e) {
            logger.info("BadCase 回流重复消息已忽略 traceId={}", message.getTraceId());
        }
    }

    /**
     * 保存标注（upsert）。
     */
    public void saveSample(EvalSampleRow row) {
        evalSampleMapper.upsert(row);
    }

    public EvalSampleRow findByTraceId(String traceId) {
        return evalSampleMapper.findByTraceId(traceId);
    }

    public List<EvalSampleRow> findGoldSet() {
        return evalSampleMapper.findGoldSet();
    }

    public List<EvalSampleRow> findBadCaseLabeled() {
        return evalSampleMapper.findBadCaseLabeled();
    }

    /**
     * 分页查询。
     */
    public List<EvalSampleRow> findBySource(String source, boolean pending, int offset, int limit) {
        return evalSampleMapper.findBySource(source, pending, offset, limit);
    }

    public int countBySourceAndStatus(String source, boolean pending) {
        return evalSampleMapper.countBySourceAndStatus(source, pending);
    }
}
