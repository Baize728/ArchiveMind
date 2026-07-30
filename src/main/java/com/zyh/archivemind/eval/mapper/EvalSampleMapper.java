package com.zyh.archivemind.eval.mapper;

import com.zyh.archivemind.eval.model.EvalSampleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * eval_samples 表的 JDBC 访问层。
 * 项目统一使用 JdbcTemplate（参考 UserFeedbackMapper / TraceService）。
 */
@Repository
public class EvalSampleMapper {

    private static final Logger logger = LoggerFactory.getLogger(EvalSampleMapper.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<EvalSampleRow> ROW_MAPPER = (rs, rowNum) -> {
        EvalSampleRow row = new EvalSampleRow();
        row.setId(rs.getLong("id"));
        row.setTraceId(rs.getString("trace_id"));
        row.setConversationId(rs.getString("conversation_id"));
        row.setExpectedIntent(rs.getString("expected_intent"));
        row.setExpectedSlots(rs.getString("expected_slots"));
        row.setExpectedClarifyAction(rs.getString("expected_clarify_action"));
        row.setExpectedAnswer(rs.getString("expected_answer"));
        row.setLabelNote(rs.getString("label_note"));
        row.setLabeledBy(rs.getString("labeled_by"));
        Timestamp ts = rs.getTimestamp("labeled_at");
        row.setLabeledAt(ts != null ? ts.toLocalDateTime() : null);
        row.setSource(rs.getString("source"));
        return row;
    };

    public EvalSampleMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * BadCase 回流插入：expected_*=null, labeled_by='system', labeled_at=now。
     * trace_id 有 UNIQUE KEY，重复插入会抛 DuplicateKeyException，由调用方捕获忽略。
     */
    public void insertBadCase(String traceId, String source, String action, String comment) {
        String sql = "INSERT INTO eval_samples (trace_id, expected_intent, expected_slots, "
                + "expected_clarify_action, expected_answer, label_note, labeled_by, labeled_at, source) "
                + "VALUES (?, NULL, NULL, NULL, NULL, ?, 'system', NOW(), ?)";
        String note = action != null ? action : "";
        if (comment != null && !comment.isBlank()) {
            note = note.isEmpty() ? comment : action + " | " + comment;
        }
        jdbcTemplate.update(sql, traceId, note, source);
    }

    /**
     * INSERT ON DUPLICATE KEY UPDATE（按 trace_id 唯一键）。
     */
    public void upsert(EvalSampleRow row) {
        String sql = "INSERT INTO eval_samples (trace_id, conversation_id, expected_intent, expected_slots, "
                + "expected_clarify_action, expected_answer, label_note, labeled_by, labeled_at, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "conversation_id = VALUES(conversation_id), "
                + "expected_intent = VALUES(expected_intent), "
                + "expected_slots = VALUES(expected_slots), "
                + "expected_clarify_action = VALUES(expected_clarify_action), "
                + "expected_answer = VALUES(expected_answer), "
                + "label_note = VALUES(label_note), "
                + "labeled_by = VALUES(labeled_by), "
                + "labeled_at = NOW()";
        // 注意：source 字段不在 UPDATE 中——保留原始来源（BADCASE_BACKFLOW 不被 MANUAL 覆盖）
        jdbcTemplate.update(sql,
                row.getTraceId(),
                row.getConversationId(),
                row.getExpectedIntent(),
                row.getExpectedSlots(),
                row.getExpectedClarifyAction(),
                row.getExpectedAnswer(),
                row.getLabelNote(),
                row.getLabeledBy(),
                row.getSource());
    }

    /**
     * 按 traceId 查询标注行。
     */
    public EvalSampleRow findByTraceId(String traceId) {
        String sql = "SELECT * FROM eval_samples WHERE trace_id = ?";
        try {
            List<EvalSampleRow> list = jdbcTemplate.query(sql, ROW_MAPPER, traceId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            logger.warn("查询 eval_samples 失败 traceId={}: {}", traceId, e.getMessage());
            return null;
        }
    }

    /**
     * Gold set：expected_answer IS NOT NULL。
     */
    public List<EvalSampleRow> findGoldSet() {
        String sql = "SELECT * FROM eval_samples WHERE expected_answer IS NOT NULL";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /**
     * BadCase 已标注：source='BADCASE_BACKFLOW' AND expected_answer IS NOT NULL。
     */
    public List<EvalSampleRow> findBadCaseLabeled() {
        String sql = "SELECT * FROM eval_samples WHERE source = 'BADCASE_BACKFLOW' AND expected_answer IS NOT NULL";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /**
     * 分页查询。
     * pending=true → expected_answer IS NULL（待标注）
     * pending=false → expected_answer IS NOT NULL（已标注）
     */
    public List<EvalSampleRow> findBySource(String source, boolean pending, int offset, int limit) {
        String sql = "SELECT * FROM eval_samples WHERE source = ? AND expected_answer IS "
                + (pending ? "NULL" : "NOT NULL")
                + " ORDER BY labeled_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, source, limit, offset);
    }

    /**
     * 统计数。
     */
    public int countBySourceAndStatus(String source, boolean pending) {
        String sql = "SELECT COUNT(*) FROM eval_samples WHERE source = ? AND expected_answer IS "
                + (pending ? "NULL" : "NOT NULL");
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, source);
        return count != null ? count : 0;
    }
}
