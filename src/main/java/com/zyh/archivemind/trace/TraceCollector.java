package com.zyh.archivemind.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Trace 采集器（核心）
 *
 * 职责：
 * 1. openTrace() 创建 TraceScope（含开关判断：onlinePersist || evalMode，以及采样）。
 * 2. persist() 在 TraceScope.close() 时调用：脱敏 + 截断 + 入内存队列（不阻塞业务热路径）。
 * 3. 后台调度按 flushIntervalMs 批量写入 MySQL，按月分表 trace_events_YYYY_MM。
 *
 * 这样在线对话全量落库，且落库写操作异步进行，满足 plan.md T0-1 验收
 * （100 QPS 下 P99<5ms、防存储爆炸、脱敏）。
 */
@Service
public class TraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(TraceCollector.class);

    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy_MM");

    /** 基表 DDL（按月分表以此 LIKE 复制） */
    private static final String BASE_TABLE_DDL =
            "CREATE TABLE IF NOT EXISTS trace_events ("
            + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + " trace_id VARCHAR(64) NOT NULL,"
            + " conversation_id VARCHAR(64),"
            + " user_id VARCHAR(64),"
            + " session_id VARCHAR(64),"
            + " step_order INT NOT NULL,"
            + " event_type VARCHAR(32) NOT NULL,"
            + " phase VARCHAR(16),"
            + " model VARCHAR(64),"
            + " input_payload TEXT,"
            + " output_payload TEXT,"
            + " input_tokens INT,"
            + " output_tokens INT,"
            + " total_tokens INT,"
            + " latency_ms BIGINT,"
            + " success TINYINT(1),"
            + " created_at DATETIME"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String INSERT_SQL =
            "INSERT INTO trace_events_%s (trace_id, conversation_id, user_id, session_id,"
            + " step_order, event_type, phase, model, input_payload, output_payload,"
            + " input_tokens, output_tokens, total_tokens, latency_ms, success, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /**
     * 期望列定义（与 BASE_TABLE_DDL 保持一致）。
     * 用于旧表增量补齐：已存在的 trace_events / trace_events_YYYY_MM 表若缺少某些列
     * （例如早期版本没有 conversation_id），启动时通过 ALTER TABLE 补齐，避免落库报
     * "Unknown column"。CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，故需此机制。
     */
    private static final Map<String, String> EXPECTED_COLUMNS = new LinkedHashMap<>();
    static {
        EXPECTED_COLUMNS.put("trace_id", "VARCHAR(64) NOT NULL");
        EXPECTED_COLUMNS.put("conversation_id", "VARCHAR(64)");
        EXPECTED_COLUMNS.put("user_id", "VARCHAR(64)");
        EXPECTED_COLUMNS.put("session_id", "VARCHAR(64)");
        EXPECTED_COLUMNS.put("step_order", "INT NOT NULL");
        EXPECTED_COLUMNS.put("event_type", "VARCHAR(32) NOT NULL");
        EXPECTED_COLUMNS.put("phase", "VARCHAR(16)");
        EXPECTED_COLUMNS.put("model", "VARCHAR(64)");
        EXPECTED_COLUMNS.put("input_payload", "TEXT");
        EXPECTED_COLUMNS.put("output_payload", "TEXT");
        EXPECTED_COLUMNS.put("input_tokens", "INT");
        EXPECTED_COLUMNS.put("output_tokens", "INT");
        EXPECTED_COLUMNS.put("total_tokens", "INT");
        EXPECTED_COLUMNS.put("latency_ms", "BIGINT");
        EXPECTED_COLUMNS.put("success", "TINYINT(1)");
        EXPECTED_COLUMNS.put("created_at", "DATETIME");
    }

    private final TraceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TraceSensitiveMasker masker;

    private final LinkedBlockingQueue<TraceEvent> queue;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "trace-flush"));
    private final Set<String> ensuredTables = ConcurrentHashMap.newKeySet();

    public TraceCollector(TraceProperties properties, DataSource dataSource) {
        this.properties = properties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.masker = new TraceSensitiveMasker(properties.isMaskEnabled(),
                properties.getSensitiveKeywords());
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
    }

    @PostConstruct
    public void init() {
        try {
            ensureBaseTable();
            ensureTableFor(LocalDate.now());
            ensureTableFor(LocalDate.now().plusMonths(1));
        } catch (Exception e) {
            // 数据库暂不可用时不要阻塞应用启动，仅告警
            logger.warn("Trace 表初始化失败（数据库可能未就绪）：{}", e.getMessage());
        }
        scheduler.scheduleAtFixedRate(this::flush,
                properties.getFlushIntervalMs(), properties.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        try {
            flush();
        } catch (Exception e) {
            logger.warn("Trace 关闭时 flush 失败：{}", e.getMessage());
        }
        scheduler.shutdown();
    }

    /**
     * 创建 TraceScope。
     * 仅当 onlinePersist 或 evalMode 开启时才采集；采样率低于 1 时按比例抽样（未命中返回 noop）。
     */
    public TraceScope openTrace(String conversationId, String userId,
                                String sessionId, boolean evalMode) {
        if (!properties.isOnlinePersist() && !evalMode) {
            return TraceScope.noop();
        }
        if (properties.getSamplingRate() < 1.0
                && ThreadLocalRandom.current().nextDouble() > properties.getSamplingRate()) {
            return TraceScope.noop();
        }
        TraceContext ctx = new TraceContext(
                UUID.randomUUID().toString().replace("-", ""),
                conversationId, userId, sessionId,
                properties.isOnlinePersist(), evalMode);
        return new TraceScope(ctx, this);
    }

    /** 由 TraceScope.close() 调用：脱敏 + 截断 + 入队（不阻塞业务线程） */
    void persist(TraceContext context) {
        if (context.getEvents().isEmpty()) {
            return;
        }
        for (TraceEvent event : context.getEvents()) {
            event.setInputPayload(maskAndTruncate(event.getInputPayload()));
            event.setOutputPayload(maskAndTruncate(event.getOutputPayload()));
            if (!queue.offer(event)) {
                logger.warn("Trace 队列已满，丢弃事件 traceId={}", event.getTraceId());
            }
        }
    }

    private String maskAndTruncate(String text) {
        String masked = masker.mask(text);
        if (masked != null && masked.length() > properties.getMaxPayloadLength()) {
            masked = masked.substring(0, properties.getMaxPayloadLength()) + "...[truncated]";
        }
        return masked;
    }

    /** 后台批量落库 */
    private void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<TraceEvent> batch = new ArrayList<>();
        queue.drainTo(batch, properties.getBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        Map<String, List<TraceEvent>> byMonth = new HashMap<>();
        for (TraceEvent e : batch) {
            String ym = ym(e.getCreatedAt());
            byMonth.computeIfAbsent(ym, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<TraceEvent>> entry : byMonth.entrySet()) {
            try {
                ensureTableFor(entry.getKey());
                insertBatch(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                logger.error("Trace 批量落库失败，月份={}：{}", entry.getKey(), e.getMessage(), e);
            }
        }
    }

    private void insertBatch(String ym, List<TraceEvent> events) {
        String sql = String.format(INSERT_SQL, ym);
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TraceEvent e = events.get(i);
                ps.setString(1, e.getTraceId());
                ps.setString(2, e.getConversationId());
                ps.setString(3, e.getUserId());
                ps.setString(4, e.getSessionId());
                ps.setInt(5, e.getStepOrder());
                ps.setString(6, e.getEventType().name());
                ps.setString(7, e.getPhase() == null ? null : e.getPhase().name());
                ps.setString(8, e.getModel());
                ps.setString(9, e.getInputPayload());
                ps.setString(10, e.getOutputPayload());
                ps.setInt(11, e.getInputTokens());
                ps.setInt(12, e.getOutputTokens());
                ps.setInt(13, e.getTotalTokens());
                ps.setLong(14, e.getLatencyMs());
                ps.setBoolean(15, e.isSuccess());
                ps.setTimestamp(16, Timestamp.valueOf(e.getCreatedAt()));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    private void ensureBaseTable() {
        if (ensuredTables.contains("base")) {
            return;
        }
        jdbcTemplate.execute(BASE_TABLE_DDL);
        ensureColumns("trace_events");
        ensuredTables.add("base");
    }

    private void ensureTableFor(LocalDate date) {
        ensureTableFor(date.format(YM_FMT));
    }

    private void ensureTableFor(String ym) {
        if (ensuredTables.contains(ym)) {
            return;
        }
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS trace_events_" + ym
                + " LIKE trace_events");
        ensureColumns("trace_events_" + ym);
        ensuredTables.add(ym);
    }

    /**
     * 增量补齐缺失列（不破坏已有数据）。
     * 旧表（早期版本创建）可能缺少 conversation_id 等列；CREATE TABLE IF NOT EXISTS 不会对
     * 已存在的表加列，故这里比对 information_schema，对缺失列 ALTER TABLE ADD COLUMN。
     */
    private void ensureColumns(String tableName) {
        Set<String> existing;
        try {
            existing = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.columns"
                            + " WHERE table_schema = DATABASE() AND table_name = ?",
                    String.class, tableName));
        } catch (Exception e) {
            logger.warn("Trace 表 {} 列信息读取失败，跳过列补齐：{}", tableName, e.getMessage());
            return;
        }
        for (Map.Entry<String, String> col : EXPECTED_COLUMNS.entrySet()) {
            if (existing.contains(col.getKey())) {
                continue;
            }
            try {
                jdbcTemplate.execute("ALTER TABLE " + tableName
                        + " ADD COLUMN " + col.getKey() + " " + col.getValue());
                logger.info("Trace 表 {} 补齐缺失列 {}", tableName, col.getKey());
            } catch (Exception e) {
                logger.error("Trace 表 {} 补齐列 {} 失败：{}", tableName, col.getKey(), e.getMessage());
            }
        }
    }

    private static String ym(LocalDateTime createdAt) {
        return createdAt == null ? LocalDate.now().format(YM_FMT) : createdAt.format(YM_FMT);
    }
}
