package com.zyh.archivemind.service;

import com.zyh.archivemind.trace.TraceEvent;
import com.zyh.archivemind.trace.TraceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Trace 查询服务（前端 Trace 详情页使用）
 * 查询按月分表 trace_events_YYYY_MM，支持按 traceId / conversationId / userId / sessionId / eventType / 时间范围筛选。
 *
 * 业务逻辑与 SQL 从 TraceController 下沉至此，Controller 仅负责参数接收与响应封装，符合项目 controller → service → repository 分层约定。
 */
@Service
public class TraceService {

    private static final Logger logger = LoggerFactory.getLogger(TraceService.class);
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public TraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 单条事件 RowMapper */
    private static final RowMapper<TraceEvent> EVENT_ROW_MAPPER = (rs, rowNum) -> TraceEvent.builder()
            .traceId(rs.getString("trace_id"))
            .conversationId(rs.getString("conversation_id"))
            .userId(rs.getString("user_id"))
            .sessionId(rs.getString("session_id"))
            .stepOrder(rs.getInt("step_order"))
            .eventType(parseEventType(rs.getString("event_type")))
            .phase(parsePhase(rs.getString("phase")))
            .model(rs.getString("model"))
            .inputPayload(rs.getString("input_payload"))
            .outputPayload(rs.getString("output_payload"))
            .inputTokens(rs.getInt("input_tokens"))
            .outputTokens(rs.getInt("output_tokens"))
            .totalTokens(rs.getInt("total_tokens"))
            .latencyMs(rs.getLong("latency_ms"))
            .success(rs.getBoolean("success"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    /**
     * Trace 列表（按 traceId 去重，返回每条会话的摘要行）
     * 支持参数：startDate, endDate, userId, sessionId, keyword, status(OK/ERROR), page, size
     *
     * 查询策略：先 UNION ALL 所有涉及的月份表，一次性查出全部匹配事件（不分页），
     * 再在内存按 traceId 聚合 + 状态过滤 + 排序 + 分页。
     * 这样避免了"每张分表各自 LIMIT/OFFSET 再合并"导致的分页错乱与数据丢失。
     */
    public TraceListResult listTraces(String traceId, String startDate, String endDate,
                                      String userId, String sessionId, String keyword,
                                      String status, Long minLatency, Long maxLatency,
                                      int page, int size) {
        // traceId 精确匹配时，直接走 1 次查询（按 traceId 拉所有事件），绕过月份表/UNION 流程
        if (traceId != null && !traceId.isBlank()) {
            return listTracesByTraceId(traceId.trim(), status, minLatency, maxLatency, page, size);
        }

        Set<String> months = resolveMonths(startDate, endDate);

        // 构建查询条件（WHERE 子句）
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (userId != null && !userId.isBlank()) {
            where.append(" AND user_id = ?");
            params.add(userId.trim());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            where.append(" AND session_id = ?");
            params.add(sessionId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (input_payload LIKE ? OR output_payload LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }

        // 两阶段查询：当 keyword 搜索时，需要"按 trace 匹配"
        // 阶段1: 找出包含关键词的事件所在的 trace_id 集合
        // 阶段2: 用这些 trace_id 重新拉取这些 trace 的全部事件（用于聚合 firstUserInput 等）
        Set<String> matchedTraceIds = null;
        if (keyword != null && !keyword.isBlank()) {
            matchedTraceIds = new HashSet<>();
            for (String month : months) {
                String tableName = "trace_events_" + month;
                if (!tableExists(tableName)) continue;
                try {
                    List<String> ids = jdbcTemplate.queryForList(
                            "SELECT DISTINCT trace_id FROM " + tableName + where,
                            String.class, params.toArray());
                    matchedTraceIds.addAll(ids);
                } catch (Exception e) {
                    logger.warn("Trace keyword 匹配 traceId 失败，月份={}：{}", month, e.getMessage());
                }
            }
            if (matchedTraceIds.isEmpty()) {
                // 没匹配到任何 trace，直接返回空
                return emptyResult();
            }
        }
        if (startDate != null && !startDate.isBlank()) {
            where.append(" AND created_at >= ?");
            params.add(startDate.trim() + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank()) {
            where.append(" AND created_at <= ?");
            params.add(endDate.trim() + " 23:59:59");
        }
        // 注意：ERROR 状态过滤放在内存阶段做（因为需要按 traceId 聚合后才能判断是否含错误事件）

        // 一次性 UNION ALL 查询所有月份表的全部匹配事件（不分页，分页在聚合后做）
        List<TraceEvent> rawEvents = new ArrayList<>();
        for (String month : months) {
            String tableName = "trace_events_" + month;
            if (!tableExists(tableName)) continue;
            try {
                String sql;
                Object[] sqlParams;
                if (matchedTraceIds != null) {
                    // keyword 模式：按匹配的 trace_id 拉取所有事件（用于聚合 firstUserInput）
                    String placeholders = String.join(",",
                            Collections.nCopies(matchedTraceIds.size(), "?"));
                    sql = String.format(
                            "SELECT * FROM %s WHERE trace_id IN (%s) ORDER BY created_at DESC, step_order ASC",
                            tableName, placeholders);
                    sqlParams = matchedTraceIds.toArray();
                } else {
                    // 非 keyword 模式：用 where 直接过滤
                    sql = String.format("SELECT * FROM %s %s ORDER BY created_at DESC, step_order ASC",
                            tableName, where);
                    sqlParams = params.toArray();
                }
                List<TraceEvent> batch = jdbcTemplate.query(sql, EVENT_ROW_MAPPER, sqlParams);
                rawEvents.addAll(batch);
            } catch (Exception e) {
                logger.warn("Trace 查询月份 {} 失败：{}", month, e.getMessage());
            }
        }

        // 按 traceId 分组聚合（同一条 trace 的多个事件合并为一条摘要）
        Map<String, TraceSummary> summaryMap = new LinkedHashMap<>();
        for (TraceEvent event : rawEvents) {
            String tid = event.getTraceId();
            TraceSummary s = summaryMap.computeIfAbsent(tid, k -> new TraceSummary());
            s.traceId = tid;
            s.conversationId = event.getConversationId();
            s.userId = event.getUserId();
            s.sessionId = event.getSessionId();
            s.createdAt = event.getCreatedAt().format(DT_FMT);
            s.eventCount++;
            s.totalInputTokens += event.getInputTokens();
            s.totalOutputTokens += event.getOutputTokens();
            s.totalLatencyMs += event.getLatencyMs();

            TraceEvent.EventType type = event.getEventType();
            if (type == TraceEvent.EventType.USER_INPUT && s.firstUserInput == null) {
                s.firstUserInput = truncate(event.getInputPayload(), 80);
            }
            if (type == TraceEvent.EventType.ERROR) {
                s.hasError = true;
                s.errorMessage = truncate(event.getOutputPayload(), 100);
            }
            if (type == TraceEvent.EventType.LLM_CALL) {
                s.llmCallCount++;
            }
            if (type == TraceEvent.EventType.TOOL_CALL && !event.isSuccess()) {
                s.hasError = true;
            }
        }

        List<TraceSummary> summaries = new ArrayList<>(summaryMap.values());

        // 延时范围过滤（totalLatencyMs 单位 ms；totalLatencyMs 来自每条 trace 事件 latencyMs 累加）
        if (minLatency != null || maxLatency != null) {
            long minMs = minLatency != null ? minLatency : 0L;
            long maxMs = maxLatency != null ? maxLatency : Long.MAX_VALUE;
            summaries.removeIf(s -> s.totalLatencyMs < minMs || s.totalLatencyMs > maxMs);
        }

        // 状态过滤（OK = 无错误事件；ERROR = 含错误事件）
        if ("OK".equalsIgnoreCase(status)) {
            summaries.removeIf(s -> s.hasError);
        } else if ("ERROR".equalsIgnoreCase(status)) {
            summaries.removeIf(s -> !s.hasError);
        }

        // 排序（按创建时间倒序）
        summaries.sort((a, b) -> b.createdAt.compareTo(a.createdAt));

        // 聚合统计（基于过滤后的全量数据）
        long totalCount = summaries.size();
        long errorCount = summaries.stream().filter(s -> s.hasError).count();
        double p50Latency = percentile(summaries, 0.5);
        double p99Latency = percentile(summaries, 0.99);
        long totalTokens = summaries.stream().mapToLong(s -> s.totalInputTokens + s.totalOutputTokens).sum();

        // 分页截断（在聚合、过滤、排序之后做，保证分页正确）
        int fromIndex = Math.min((page - 1) * size, summaries.size());
        int toIndex = Math.min(fromIndex + size, summaries.size());
        List<TraceSummary> pageData = new ArrayList<>(summaries.subList(fromIndex, toIndex));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("errorCount", errorCount);
        stats.put("p50Latency", formatLatency(p50Latency));
        stats.put("p99Latency", formatLatency(p99Latency));
        stats.put("totalTokens", formatTokens(totalTokens));

        TraceListResult result = new TraceListResult();
        result.list = pageData;
        result.total = totalCount;
        result.stats = stats;
        return result;
    }

    /** 空结果（keyword 没匹配到任何 trace 时直接返回，避免后续空指针） */
    private TraceListResult emptyResult() {
        TraceListResult result = new TraceListResult();
        result.list = new ArrayList<>();
        result.total = 0;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", 0);
        stats.put("errorCount", 0);
        stats.put("p50Latency", "0ms");
        stats.put("p99Latency", "0ms");
        stats.put("totalTokens", "0");
        result.stats = stats;
        return result;
    }

    /**
     * 按 traceId 精确匹配查询（简化路径）
     * 在最近 3 个月表里查找，内存聚合为 summary，应用 status 过滤和分页。
     * 用于 listTraces 入口 traceId 非空时的快速查询。
     */
    private TraceListResult listTracesByTraceId(String traceId, String status, Long minLatency, Long maxLatency, int page, int size) {
        List<TraceEvent> events = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            String month = now.minusMonths(i).format(YM_FMT);
            String tableName = "trace_events_" + month;
            if (!tableExists(tableName)) continue;
            try {
                List<TraceEvent> batch = jdbcTemplate.query(
                        "SELECT * FROM " + tableName + " WHERE trace_id = ? ORDER BY step_order ASC",
                        EVENT_ROW_MAPPER, traceId);
                events.addAll(batch);
            } catch (Exception e) {
                logger.warn("Trace 详情查询月份 {} 失败：{}", month, e.getMessage());
            }
        }

        if (events.isEmpty()) {
            return emptyResult();
        }

        // 按 traceId 聚合为单条 summary
        TraceSummary s = new TraceSummary();
        s.traceId = events.get(0).getTraceId();
        s.conversationId = events.get(0).getConversationId();
        s.userId = events.get(0).getUserId();
        s.sessionId = events.get(0).getSessionId();
        s.createdAt = events.get(0).getCreatedAt().format(DT_FMT);
        for (TraceEvent event : events) {
            s.eventCount++;
            s.totalInputTokens += event.getInputTokens();
            s.totalOutputTokens += event.getOutputTokens();
            s.totalLatencyMs += event.getLatencyMs();
            TraceEvent.EventType type = event.getEventType();
            if (type == TraceEvent.EventType.USER_INPUT && s.firstUserInput == null) {
                s.firstUserInput = truncate(event.getInputPayload(), 80);
            }
            if (type == TraceEvent.EventType.ERROR) {
                s.hasError = true;
                s.errorMessage = truncate(event.getOutputPayload(), 100);
            }
            if (type == TraceEvent.EventType.LLM_CALL) {
                s.llmCallCount++;
            }
            if (type == TraceEvent.EventType.TOOL_CALL && !event.isSuccess()) {
                s.hasError = true;
            }
        }

        // 延时范围过滤
        if (minLatency != null || maxLatency != null) {
            long minMs = minLatency != null ? minLatency : 0L;
            long maxMs = maxLatency != null ? maxLatency : Long.MAX_VALUE;
            if (s.totalLatencyMs < minMs || s.totalLatencyMs > maxMs) {
                return emptyResult();
            }
        }

        // status 过滤
        List<TraceSummary> summaries = new ArrayList<>();
        if ("OK".equalsIgnoreCase(status) && s.hasError) {
            // 过滤掉
        } else if ("ERROR".equalsIgnoreCase(status) && !s.hasError) {
            // 过滤掉
        } else {
            summaries.add(s);
        }

        long totalCount = summaries.size();
        long errorCount = summaries.stream().filter(x -> x.hasError).count();
        double p50Latency = percentile(summaries, 0.5);
        double p99Latency = percentile(summaries, 0.99);
        long totalTokens = summaries.stream().mapToLong(x -> x.totalInputTokens + x.totalOutputTokens).sum();

        // 分页（traceId 精确匹配通常只有 1 条，但保留分页逻辑保持一致）
        int fromIndex = Math.min((page - 1) * size, summaries.size());
        int toIndex = Math.min(fromIndex + size, summaries.size());
        List<TraceSummary> pageData = new ArrayList<>(summaries.subList(fromIndex, toIndex));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", totalCount);
        stats.put("errorCount", errorCount);
        stats.put("p50Latency", formatLatency(p50Latency));
        stats.put("p99Latency", formatLatency(p99Latency));
        stats.put("totalTokens", formatTokens(totalTokens));

        TraceListResult result = new TraceListResult();
        result.list = pageData;
        result.total = totalCount;
        result.stats = stats;
        return result;
    }

    /**
     * Trace 详情（按 traceId 返回所有事件，按 stepOrder 排序）
     * 未查到任何事件时返回 null。
     */
    public TraceDetailResult getTraceDetail(String traceId) {
        // 遍历最近 3 个月表查找
        List<TraceEvent> events = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            String month = now.minusMonths(i).format(YM_FMT);
            String tableName = "trace_events_" + month;
            try {
                if (!tableExists(tableName)) continue;
                List<TraceEvent> batch = jdbcTemplate.query(
                        "SELECT * FROM " + tableName + " WHERE trace_id = ? ORDER BY step_order ASC",
                        EVENT_ROW_MAPPER, traceId);
                events.addAll(batch);
                if (!batch.isEmpty()) break; // 找到就停止
            } catch (Exception e) {
                logger.warn("Trace 详情查询月份 {} 失败：{}", month, e.getMessage());
            }
        }

        if (events.isEmpty()) {
            return null;
        }
        logger.debug("Trace 详情 traceId={} 共 {} 个事件, 第一个: type={}, input={}", traceId, events.size(),
                events.get(0).getEventType(), events.get(0).getInputPayload());

        // 聚合头部信息
        TraceEvent first = events.get(0);
        TraceEvent last = events.get(events.size() - 1);
        long totalLatencyMs = events.stream().mapToLong(TraceEvent::getLatencyMs).sum();
        int totalInputTokens = events.stream().mapToInt(TraceEvent::getInputTokens).sum();
        int totalOutputTokens = events.stream().mapToInt(TraceEvent::getOutputTokens).sum();
        long llmCallCount = events.stream()
                .filter(e -> e.getEventType() == TraceEvent.EventType.LLM_CALL).count();
        boolean hasError = events.stream()
                .anyMatch(e -> e.getEventType() == TraceEvent.EventType.ERROR || !e.isSuccess());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", traceId);
        summary.put("conversationId", first.getConversationId());
        summary.put("userId", first.getUserId());
        summary.put("sessionId", first.getSessionId());
        summary.put("status", hasError ? "ERROR" : "SUCCESS");
        summary.put("startTime", first.getCreatedAt().format(DT_FMT));
        summary.put("endTime", last.getCreatedAt().format(DT_FMT));
        summary.put("durationMs", totalLatencyMs);
        summary.put("eventCount", events.size());
        summary.put("llmCallCount", llmCallCount);
        summary.put("inputTokens", totalInputTokens);
        summary.put("outputTokens", totalOutputTokens);
        summary.put("totalTokens", totalInputTokens + totalOutputTokens);

        TraceDetailResult result = new TraceDetailResult();
        result.summary = summary;
        result.events = events;
        return result;
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /** 解析时间范围涉及的月份 */
    private Set<String> resolveMonths(String startDate, String endDate) {
        Set<String> months = new LinkedHashSet<>();
        months.add(LocalDateTime.now().format(YM_FMT)); // 至少包含当月
        if (startDate != null && !startDate.isBlank()) {
            try {
                LocalDate sd = LocalDate.parse(startDate.trim());
                LocalDate ed = (endDate != null && !endDate.isBlank())
                        ? LocalDate.parse(endDate.trim()) : LocalDate.now();
                LocalDate cur = sd.withDayOfMonth(1);
                while (!cur.isAfter(ed)) {
                    months.add(cur.format(YM_FMT));
                    cur = cur.plusMonths(1);
                }
            } catch (Exception ignored) {
            }
        }
        return months;
    }

    private boolean tableExists(String tableName) {
        try {
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ? LIMIT 1",
                    Integer.class, tableName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildUnionAllQuery(Set<String> months, String where, boolean isCount) {
        String select = isCount ? "SELECT COUNT(*) as cnt"
                : "SELECT *";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String m : months) {
            if (!first) sb.append(" UNION ALL ");
            sb.append(select).append(" FROM trace_events_").append(m).append(where);
            first = false;
        }
        return sb.length() > 0 ? sb.toString() : select + " FROM trace_events " + where;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private static double percentile(List<TraceSummary> list, double pct) {
        if (list.isEmpty()) return 0;
        List<Long> latencies = list.stream()
                .map(s -> s.totalLatencyMs)
                .sorted()
                .toList();
        int idx = (int) Math.ceil(pct * latencies.size()) - 1;
        return latencies.get(Math.max(0, idx));
    }

    private static String formatLatency(double ms) {
        if (ms < 1000) return String.format("%.2fms", ms);
        if (ms < 60000) return String.format("%.2fs", ms / 1000);
        return String.format("%.2fm", ms / 60000);
    }

    private static String formatTokens(long tokens) {
        if (tokens < 1024) return tokens + "";
        if (tokens < 1024 * 1024) return String.format("%.2fK", tokens / 1024.0);
        return String.format("%.2fM", tokens / (1024.0 * 1024));
    }

    /** Trace 列表结果（供 Controller 封装响应） */
    public static class TraceListResult {
        public List<TraceSummary> list;
        public long total;
        public Map<String, Object> stats;
    }

    /** Trace 详情结果（供 Controller 封装响应） */
    public static class TraceDetailResult {
        public Map<String, Object> summary;
        public List<TraceEvent> events;
    }

    /** 容错解析事件类型枚举，兼容 DB 旧数据中已废弃的枚举值（如 AGENT_COMPLETE/AGENT_START） */
    private static TraceEvent.EventType parseEventType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TraceEvent.EventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 已废弃枚举值（AGENT_START/AGENT_COMPLETE 等）降级为 LEGACY，不返回 null 避免前端处理麻烦
            return TraceEvent.EventType.LEGACY;
        }
    }

    /** 容错解析阶段枚举 */
    private static TraceEvent.Phase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TraceEvent.Phase.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
