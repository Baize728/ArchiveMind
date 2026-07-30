package com.zyh.archivemind.feedback.mapper;

import com.zyh.archivemind.feedback.model.UserFeedbackRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * user_feedback 表的 JDBC 访问层。
 * 项目 trace 模块统一使用 JdbcTemplate，此处保持一致。
 */
@Repository
public class UserFeedbackMapper {

    private static final Logger logger = LoggerFactory.getLogger(UserFeedbackMapper.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<UserFeedbackRow> ROW_MAPPER = (rs, rowNum) -> {
        UserFeedbackRow row = new UserFeedbackRow();
        row.setId(rs.getLong("id"));
        row.setUserId(rs.getString("user_id"));
        row.setConversationId(rs.getString("conversation_id"));
        row.setTraceId(rs.getString("trace_id"));
        row.setAction(rs.getString("action"));
        row.setRating(rs.getObject("rating", Integer.class));
        row.setComment(rs.getString("comment"));
        Timestamp ts = rs.getTimestamp("created_at");
        row.setCreatedAt(ts != null ? ts.toLocalDateTime() : null);
        return row;
    };

    public UserFeedbackMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条用户反馈记录，返回自增主键 ID。
     */
    public long insert(UserFeedbackRow row) {
        String sql = "INSERT INTO user_feedback (user_id, conversation_id, trace_id, action, rating, comment, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, NOW())";
        try {
            return jdbcTemplate.update(sql,
                    row.getUserId(),
                    row.getConversationId(),
                    row.getTraceId(),
                    row.getAction(),
                    row.getRating(),
                    row.getComment());
        } catch (Exception e) {
            logger.error("插入 user_feedback 失败 traceId={}: {}", row.getTraceId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 按 traceId 查询全部反馈（同一 trace 可能有多条反馈）。
     */
    public List<UserFeedbackRow> findByTraceId(String traceId) {
        String sql = "SELECT * FROM user_feedback WHERE trace_id = ? ORDER BY created_at DESC";
        try {
            return jdbcTemplate.query(sql, ROW_MAPPER, traceId);
        } catch (Exception e) {
            logger.warn("查询 user_feedback 失败 traceId={}: {}", traceId, e.getMessage());
            return List.of();
        }
    }
}
