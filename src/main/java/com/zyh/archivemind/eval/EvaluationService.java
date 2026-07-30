package com.zyh.archivemind.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.config.EvalProperties;
import com.zyh.archivemind.eval.mapper.EvalSampleMapper;
import com.zyh.archivemind.eval.model.*;
import com.zyh.archivemind.feedback.mapper.UserFeedbackMapper;
import com.zyh.archivemind.feedback.model.UserFeedbackRow;
import com.zyh.archivemind.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 评测主服务。
 *
 * 职责：
 * - parseTrace：从 trace_events 事件列表解析评估快照
 * - computeRuleMetrics：8 个规则指标
 * - aggregateJudgeScore / feedbackScore / weightedScore：总分聚合
 * - 异步任务管理（@Async + ConcurrentHashMap）
 * - 回归 pass 判定 + 报告生成
 *
 * 不做的事：不构造 Judge prompt、不直接调 LLM（归 JudgeService）。
 */
@Service
public class EvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationService.class);
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final int DEFAULT_LIMIT = 1000;
    private static final List<String> SLOT_NAMES = List.of("domain", "docScope", "timeRange", "entity");

    private final JudgeService judgeService;
    private final EvalSampleMapper evalSampleMapper;
    private final UserFeedbackMapper userFeedbackMapper;
    private final EvalProperties evalProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 异步任务表 */
    private final ConcurrentHashMap<String, EvalTask> tasks = new ConcurrentHashMap<>();

    /** 自注入以解决 @Async 自调用代理失效问题 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private EvaluationService self;

    public EvaluationService(JudgeService judgeService,
                             EvalSampleMapper evalSampleMapper,
                             UserFeedbackMapper userFeedbackMapper,
                             EvalProperties evalProperties,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.judgeService = judgeService;
        this.evalSampleMapper = evalSampleMapper;
        this.userFeedbackMapper = userFeedbackMapper;
        this.evalProperties = evalProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── 异步任务管理 ──────────────────────────────────────────

    /**
     * 创建评测任务（同步），返回 taskId。
     * 实际评测在 doEvaluateAsync 中异步执行。
     */
    public String createTask(EvalRequest request) {
        String taskId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        EvalTask task = new EvalTask(taskId, EvalTask.Status.RUNNING);
        tasks.put(taskId, task);
        // 通过自注入代理调用，确保 @Async 生效（同类内部直接调用不走 AOP 代理）
        self.doEvaluateAsync(taskId, request);
        return taskId;
    }

    /**
     * 异步执行评测（@Async 仅支持 void/Future，不能返回 String）。
     */
    @Async
    public void doEvaluateAsync(String taskId, EvalRequest request) {
        EvalTask task = tasks.get(taskId);
        if (task == null) return;
        try {
            doEvaluate(task, request);
        } catch (Exception e) {
            logger.error("评测任务失败 taskId={}: {}", taskId, e.getMessage(), e);
            task.fail(e.getMessage());
        }
    }

    public EvalTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 定时清理过期任务（每小时一次）。
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupStaleTasks() {
        int retentionHours = evalProperties.getTask().getRetentionHours();
        tasks.entrySet().removeIf(entry -> {
            EvalTask task = entry.getValue();
            return (task.getStatus() == EvalTask.Status.COMPLETED || task.getStatus() == EvalTask.Status.FAILED)
                    && task.isExpired(retentionHours);
        });
    }

    // ── 核心评测逻辑 ──────────────────────────────────────────

    private void doEvaluate(EvalTask task, EvalRequest request) {
        String mode = request.getMode() == null ? "TIME_RANGE" : request.getMode();
        boolean includeJudge = Boolean.TRUE.equals(request.getIncludeJudge());
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();

        // 1. 根据 mode 取数据源
        List<String> traceIds;
        List<EvalSampleRow> samples = List.of();

        switch (mode) {
            case "GOLD_SET":
                samples = evalSampleMapper.findGoldSet();
                traceIds = samples.stream().map(EvalSampleRow::getTraceId).collect(Collectors.toList());
                break;
            case "BADCASE_ONLY":
                samples = evalSampleMapper.findBadCaseLabeled();
                traceIds = samples.stream().map(EvalSampleRow::getTraceId).collect(Collectors.toList());
                break;
            case "TIME_RANGE":
            default:
                traceIds = findTraceIdsByTimeRange(request.getStartAt(), request.getEndAt(), request.getUserId(), limit);
                break;
        }

        // Map traceId → sample（可能为空，TIME_RANGE 模式下没有标注）
        Map<String, EvalSampleRow> sampleByTraceId = samples.stream()
                .collect(Collectors.toMap(EvalSampleRow::getTraceId, s -> s, (a, b) -> a));

        task.getTotal().set(traceIds.size());

        // 2. 逐条评测
        List<TraceEvalResult> results = new ArrayList<>();
        List<EvalReport.FailedTrace> failedTraces = new ArrayList<>();

        for (String traceId : traceIds) {
            try {
                EvalSampleRow sample = sampleByTraceId.get(traceId);
                TraceEvalResult result = evaluateTrace(traceId, sample, includeJudge);
                results.add(result);

                // 收集失败 trace
                if (result.getScore() != null && result.getScore() < evalProperties.getRegression().getPassThreshold()) {
                    String failReason = determineFailReason(result);
                    failedTraces.add(new EvalReport.FailedTrace(traceId, result.getScore(), failReason));
                }
            } catch (Exception e) {
                logger.warn("单条 trace 评测失败 traceId={}: {}", traceId, e.getMessage());
            } finally {
                task.getDone().incrementAndGet();
            }
        }

        // 3. 汇总
        Double averageScore = average(results.stream().map(TraceEvalResult::getScore).toList());
        int labeledTraces = (int) results.stream().filter(r -> r.getRuleScore() != null || r.getJudgeScore() != null).count();
        double labelCoverage = results.isEmpty() ? 0.0 : (double) labeledTraces / results.size();

        Map<String, MetricStat> metricAverages = metricAverages(results);

        // pass 判定
        boolean pass = true;
        String passReason = "PASS";
        double passThreshold = evalProperties.getRegression().getPassThreshold();

        if (averageScore == null || averageScore < passThreshold) {
            pass = false;
            passReason = "averageScore " + (averageScore == null ? "null" : averageScore) + " < passThreshold " + passThreshold;
        }
        // hallucination 检查
        boolean hasHallucinationFail = failedTraces.stream()
                .anyMatch(f -> "hallucination".equals(f.getFailReason()));
        if (hasHallucinationFail) {
            pass = false;
            passReason = "存在 hallucinationControl=0 的 trace";
        }

        EvalReport report = new EvalReport();
        report.setStartAt(request.getStartAt());
        report.setEndAt(request.getEndAt());
        report.setTotalTraces(results.size());
        report.setLabeledTraces(labeledTraces);
        report.setLabelCoverage(labelCoverage);
        report.setAverageScore(averageScore);
        report.setPass(pass);
        report.setPassReason(passReason);
        report.setMetricAverages(metricAverages);
        report.setDetails(results);
        report.setFailedTraces(failedTraces);

        task.complete(report);
    }

    /**
     * 单条 trace 评测。
     */
    private TraceEvalResult evaluateTrace(String traceId, EvalSampleRow sample, boolean includeJudge) {
        // 查 trace 事件
        List<TraceEvent> events = findTraceEvents(traceId);
        TraceSnapshot snapshot = parseTrace(events);

        // 规则指标
        Map<String, Double> metrics = computeRuleMetrics(snapshot, sample, null);

        // Judge
        JudgeResult judge = null;
        if (includeJudge) {
            try {
                judge = judgeService.judge(traceId, snapshot, sample);
            } catch (Exception e) {
                logger.warn("Judge 调用失败 traceId={}: {}", traceId, e.getMessage());
            }
        }

        // 补充 hallucinationControl（依赖 Judge faithfulness）
        if (judge != null) {
            metrics.put("hallucinationControl", judge.getFaithfulness() >= 3 ? 1.0 : 0.0);
        }

        // 用户反馈
        List<UserFeedbackRow> feedbacks = userFeedbackMapper.findByTraceId(traceId);
        Double feedbackScore = feedbackScore(feedbacks);

        // 规则分
        Double ruleScore = groupAverage(metrics, List.of(
                "intentAccuracy", "slotAccuracy", "clarifyNecessityAccuracy",
                "tokenCostScore", "latencyScore", "fallbackScore",
                "hallucinationControl", "multiTurnConsistency"
        ));

        // Judge 分
        Double judgeScore = judge == null ? null : aggregateJudgeScore(judge);

        // 总分
        Double score = weightedScore(ruleScore, judgeScore, feedbackScore);

        // detail
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("predictedIntent", snapshot.getIntent());
        detail.put("predictedSlots", snapshot.getSlots());
        detail.put("predictedClarifyAction", snapshot.getClarifyAction());
        detail.put("expectedIntent", sample != null ? sample.getExpectedIntent() : null);
        detail.put("expectedSlots", sample != null ? sample.getExpectedSlots() : null);
        detail.put("expectedClarifyAction", sample != null ? sample.getExpectedClarifyAction() : null);
        detail.put("feedbackCount", feedbacks.size());
        detail.put("judgeMode", includeJudge ? "LLM_AS_JUDGE" : "DISABLED");
        detail.put("judgeReason", judge != null ? judge.getReason() : null);
        detail.put("citedChunks", snapshot.getCitedChunks());
        detail.put("tokenCost", snapshot.getTokenCost());
        detail.put("fallbackUsed", snapshot.isFallbackUsed());

        LocalDateTime createdAt = events.isEmpty() ? LocalDateTime.now() : events.get(0).getCreatedAt();
        String conversationId = events.isEmpty() ? null : events.get(0).getConversationId();

        return new TraceEvalResult(
                traceId,
                conversationId,
                createdAt,
                toPercent(score),
                toPercent(ruleScore),
                toPercent(judgeScore),
                toPercent(feedbackScore),
                metrics,
                detail
        );
    }

    // ── parseTrace（Q39 事件映射）──────────────────────────────

    private TraceSnapshot parseTrace(List<TraceEvent> events) {
        String intent = null;
        Map<String, String> slots = new LinkedHashMap<>();
        String clarifyAction = null;
        List<CitedChunk> citedChunks = new ArrayList<>();
        long tokenCost = 0;
        boolean hasToken = false;
        boolean fallbackUsed = false;
        String finalText = null;
        String userInput = null;
        long latencyMs = 0;

        for (TraceEvent event : events) {
            TraceEvent.EventType eventType = event.getEventType();
            if (eventType == null) continue;

            // ERROR 或任意 success=false → fallbackUsed
            if (eventType == TraceEvent.EventType.ERROR || !event.isSuccess()) {
                fallbackUsed = true;
            }

            JsonNode output = payload(event.getOutputPayload());
            JsonNode input = payload(event.getInputPayload());

            switch (eventType) {
                case INTENT_RECOGNIZED:
                    intent = output.path("intent").asText(intent);
                    break;
                case CLARIFY:
                    clarifyAction = output.path("action").asText(clarifyAction);
                    // slots（4 维：domain/docScope/timeRange/entity）
                    JsonNode slotsNode = output.path("slots");
                    if (slotsNode.isObject()) {
                        slots = parseSlotsMap(slotsNode);
                    }
                    break;
                case TOOL_CALL:
                    // 判断是否是 knowledge_search（检查 inputPayload）
                    if (isKnowledgeSearch(event.getInputPayload())) {
                        citedChunks = parseCitedChunks(output);
                    }
                    break;
                case LLM_CALL:
                    if (event.getTotalTokens() > 0) {
                        tokenCost += event.getTotalTokens();
                        hasToken = true;
                    }
                    // finalText 覆盖为 outputPayload
                    String llmOutput = output.asText(null);
                    if (llmOutput != null && !llmOutput.isBlank()) {
                        finalText = llmOutput;
                    }
                    break;
                case USER_INPUT:
                    userInput = event.getInputPayload();
                    break;
                case AGENT_DURATION:
                    latencyMs = event.getLatencyMs();
                    break;
                default:
                    break;
            }
        }

        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setIntent(intent);
        snapshot.setSlots(slots);
        snapshot.setClarifyAction(clarifyAction);
        snapshot.setCitedChunks(citedChunks);
        snapshot.setTokenCost(hasToken ? tokenCost : null);
        snapshot.setFallbackUsed(fallbackUsed);
        snapshot.setFinalText(finalText);
        snapshot.setUserInput(userInput);
        return snapshot;
    }

    // ── computeRuleMetrics（Q30 指标）─────────────────────────

    private Map<String, Double> computeRuleMetrics(TraceSnapshot snapshot, EvalSampleRow sample, JudgeResult judge) {
        Map<String, Double> metrics = new LinkedHashMap<>();

        // intentAccuracy
        metrics.put("intentAccuracy", intentAccuracy(
                sample != null ? sample.getExpectedIntent() : null, snapshot.getIntent()));

        // slotAccuracy
        metrics.put("slotAccuracy", slotAccuracy(
                sample != null ? sample.getExpectedSlots() : null, snapshot.getSlots()));

        // clarifyNecessityAccuracy
        metrics.put("clarifyNecessityAccuracy", clarifyNecessityAccuracy(
                sample != null ? sample.getExpectedClarifyAction() : null, snapshot.getClarifyAction()));

        // tokenCostScore
        metrics.put("tokenCostScore", tokenCostScore(snapshot.getTokenCost()));

        // latencyScore
        metrics.put("latencyScore", latencyScore(null)); // latencyMs 从 AGENT_DURATION 取，暂无

        // fallbackScore
        metrics.put("fallbackScore", snapshot.isFallbackUsed() ? 0.0 : 1.0);

        // hallucinationControl（依赖 Judge，此处先放 null，evaluateTrace 中补充）
        metrics.put("hallucinationControl", judge != null
                ? (judge.getFaithfulness() >= 3 ? 1.0 : 0.0) : null);

        // multiTurnConsistency（一期返回 null）
        metrics.put("multiTurnConsistency", null);

        return metrics;
    }

    private Double intentAccuracy(String expectedIntent, String actualIntent) {
        if (!hasText(expectedIntent)) return null;
        return expectedIntent.equals(actualIntent) ? 1.0 : 0.0;
    }

    private Double slotAccuracy(String expectedSlotsJson, Map<String, String> actualSlots) {
        if (!hasText(expectedSlotsJson)) return null;
        JsonNode expectedNode = readTree(expectedSlotsJson);
        Map<String, String> expectedSlots = parseSlotsMap(expectedNode);

        int compared = 0;
        int matched = 0;
        for (String slotName : SLOT_NAMES) {
            String expected = expectedSlots.get(slotName);
            if (!hasText(expected)) continue;
            compared++;
            String actual = actualSlots.getOrDefault(slotName, "");
            if (expected.equals(actual)) matched++;
        }
        return compared == 0 ? null : matched / (double) compared;
    }

    private Double clarifyNecessityAccuracy(String expectedAction, String actualAction) {
        if (!hasText(expectedAction)) return null;
        return expectedAction.equals(actualAction) ? 1.0 : 0.0;
    }

    private Double tokenCostScore(Long tokenCost) {
        if (tokenCost == null) return null;
        if (tokenCost <= 1000) return 1.0;
        if (tokenCost >= 3000) return 0.0;
        return (3000.0 - tokenCost) / 2000.0;
    }

    private Double latencyScore(Long latencyMs) {
        if (latencyMs == null) return null;
        if (latencyMs <= 3000) return 1.0;
        if (latencyMs >= 8000) return 0.0;
        return (8000.0 - latencyMs) / 5000.0;
    }

    // ── aggregateJudgeScore（Q27/Q42）─────────────────────────

    private Double aggregateJudgeScore(JudgeResult judge) {
        EvalProperties.JudgeWeights w = evalProperties.getJudge().getWeights();
        double f = judge.getFaithfulness() / 5.0;
        double r = judge.getRelevance() / 5.0;
        double c = judge.getCompleteness() / 5.0;
        double n = judge.getNaturalness() / 5.0;

        if (judge.getCorrectness() != null) {
            double corr = judge.getCorrectness() / 5.0;
            return f * w.getFaithfulnessWithCorrectness()
                    + r * w.getRelevanceWithCorrectness()
                    + c * w.getCompletenessWithCorrectness()
                    + corr * w.getCorrectness()
                    + n * w.getNaturalnessWithCorrectness();
        } else {
            return f * w.getFaithfulness()
                    + r * w.getRelevance()
                    + c * w.getCompleteness()
                    + n * w.getNaturalness();
        }
    }

    // ── feedbackScore（Q28）──────────────────────────────────

    private Double feedbackScore(List<UserFeedbackRow> feedbacks) {
        List<Double> scores = feedbacks.stream()
                .map(this::singleFeedbackScore)
                .filter(Objects::nonNull)
                .toList();
        return average(scores);
    }

    private Double singleFeedbackScore(UserFeedbackRow feedback) {
        if (feedback.getRating() != null) {
            return Math.max(0, Math.min(5, feedback.getRating())) / 5.0;
        }
        String action = feedback.getAction() == null ? "" : feedback.getAction().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "LIKE" -> 1.0;
            case "DISLIKE" -> 0.0;
            case "PARTIAL_CORRECT" -> 0.5;
            case "OUTDATED" -> 0.3;
            default -> null;
        };
    }

    // ── weightedScore（Q26 动态归一）──────────────────────────

    private Double weightedScore(Double ruleScore, Double judgeScore, Double feedbackScore) {
        EvalProperties.Weights w = evalProperties.getWeights();
        double weighted = 0.0;
        double weight = 0.0;

        if (ruleScore != null) {
            weighted += ruleScore * w.getRule();
            weight += w.getRule();
        }
        if (judgeScore != null) {
            weighted += judgeScore * w.getJudge();
            weight += w.getJudge();
        }
        if (feedbackScore != null) {
            weighted += feedbackScore * w.getFeedback();
            weight += w.getFeedback();
        }
        return weight == 0.0 ? null : weighted / weight;
    }

    // ── metricAverages（Q35）──────────────────────────────────

    private Map<String, MetricStat> metricAverages(List<TraceEvalResult> results) {
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (TraceEvalResult result : results) {
            if (result.getMetrics() == null) continue;
            result.getMetrics().forEach((name, value) -> {
                if (value != null) {
                    values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                }
            });
        }

        Map<String, MetricStat> averages = new LinkedHashMap<>();
        values.forEach((name, metricValues) -> {
            Double avg = average(metricValues);
            String scope = null;
            if ("tokenCost".equals(name) || "tokenCostScore".equals(name)) {
                scope = "answer_generate_only";
            }
            averages.put(name, new MetricStat(avg, metricValues.size(), scope));
        });
        return averages;
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private String determineFailReason(TraceEvalResult result) {
        if (result.getMetrics() != null) {
            Double hallucinationControl = result.getMetrics().get("hallucinationControl");
            if (hallucinationControl != null && hallucinationControl == 0.0) {
                return "hallucination";
            }
        }
        return "score_below_threshold";
    }

    private Double groupAverage(Map<String, Double> metrics, List<String> names) {
        return average(names.stream().map(metrics::get).toList());
    }

    private Double average(List<Double> values) {
        List<Double> present = values.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) return null;
        return present.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private Double toPercent(Double score) {
        return score == null ? null : Math.round(score * 10000.0) / 100.0;
    }

    // ── trace_events 查询 ─────────────────────────────────────

    /**
     * 按时间范围从 trace_events 按月分表查 trace_id 列表（去重）。
     */
    private List<String> findTraceIdsByTimeRange(LocalDateTime startAt, LocalDateTime endAt, String userId, int limit) {
        Set<String> months = resolveMonths(startAt, endAt);
        Set<String> traceIds = new LinkedHashSet<>();

        for (String month : months) {
            String tableName = "trace_events_" + month;
            if (!tableExists(tableName)) continue;
            try {
                StringBuilder sql = new StringBuilder("SELECT DISTINCT trace_id FROM " + tableName + " WHERE 1=1");
                List<Object> params = new ArrayList<>();
                if (userId != null && !userId.isBlank()) {
                    sql.append(" AND user_id = ?");
                    params.add(userId);
                }
                if (startAt != null) {
                    sql.append(" AND created_at >= ?");
                    params.add(startAt);
                }
                if (endAt != null) {
                    sql.append(" AND created_at <= ?");
                    params.add(endAt);
                }
                sql.append(" LIMIT ?");
                params.add(limit);

                List<String> batch = jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray());
                traceIds.addAll(batch);
                if (traceIds.size() >= limit) break;
            } catch (Exception e) {
                logger.warn("查询 trace_id 月份 {} 失败：{}", month, e.getMessage());
            }
        }

        return traceIds.stream().limit(limit).toList();
    }

    /**
     * 按 traceId 查所有事件（遍历最近 3 个月表）。
     */
    private List<TraceEvent> findTraceEvents(String traceId) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            String month = now.minusMonths(i).format(YM_FMT);
            String tableName = "trace_events_" + month;
            if (!tableExists(tableName)) continue;
            try {
                List<TraceEvent> batch = jdbcTemplate.query(
                        "SELECT * FROM " + tableName + " WHERE trace_id = ? ORDER BY step_order ASC",
                        TRACE_EVENT_ROW_MAPPER, traceId);
                if (!batch.isEmpty()) return batch;
            } catch (Exception e) {
                logger.warn("查询 trace 事件月份 {} 失败 traceId={}: {}", month, traceId, e.getMessage());
            }
        }
        return List.of();
    }

    private static final RowMapper<TraceEvent> TRACE_EVENT_ROW_MAPPER = (rs, rowNum) -> TraceEvent.builder()
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

    private static TraceEvent.EventType parseEventType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TraceEvent.EventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return TraceEvent.EventType.LEGACY;
        }
    }

    private static TraceEvent.Phase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TraceEvent.Phase.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Set<String> resolveMonths(LocalDateTime startAt, LocalDateTime endAt) {
        Set<String> months = new LinkedHashSet<>();
        months.add(LocalDateTime.now().format(YM_FMT));
        if (startAt != null && endAt != null) {
            LocalDate sd = startAt.toLocalDate().withDayOfMonth(1);
            LocalDate ed = endAt.toLocalDate();
            LocalDate cur = sd;
            while (!cur.isAfter(ed)) {
                months.add(cur.format(YM_FMT));
                cur = cur.plusMonths(1);
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

    // ── JSON 解析辅助 ─────────────────────────────────────────

    private JsonNode payload(String payload) {
        if (!hasText(payload)) return objectMapper.createObjectNode();
        return readTree(payload);
    }

    private JsonNode readTree(String json) {
        if (!hasText(json)) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private Map<String, String> parseSlotsMap(JsonNode node) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            JsonNode val = node.path(slotName);
            if (!val.isMissingNode() && !val.isNull()) {
                slots.put(slotName, val.asText(""));
            }
        }
        return slots;
    }

    private boolean isKnowledgeSearch(String inputPayload) {
        return inputPayload != null && inputPayload.contains("knowledge_search");
    }

    private List<CitedChunk> parseCitedChunks(JsonNode output) {
        if (!output.isArray()) return List.of();
        List<CitedChunk> chunks = new ArrayList<>();
        for (JsonNode item : output) {
            CitedChunk chunk = new CitedChunk();
            chunk.setChunkId(item.path("chunkId").asInt());
            chunk.setFileMd5(item.path("fileMd5").asText(null));
            chunk.setContent(item.path("content").asText(null));
            double score = item.path("score").asDouble(0.0);
            chunk.setScore(score);
            chunks.add(chunk);
        }
        return chunks;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
