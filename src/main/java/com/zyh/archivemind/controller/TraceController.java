package com.zyh.archivemind.controller;

import com.zyh.archivemind.service.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Trace 查询接口（前端 Trace 详情页使用）
 * 仅负责参数接收与响应封装，查询逻辑下沉至 {@link TraceService}。
 */
@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {

    private static final Logger logger = LoggerFactory.getLogger(TraceController.class);

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * Trace 列表（按 traceId 去重，返回每条会话的摘要行）
     * 支持参数：traceId, startDate, endDate, userId, sessionId, keyword,
     *           status(OK/ERROR), minLatency, maxLatency（单位 ms）, page, size
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listTraces(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long minLatency,
            @RequestParam(required = false) Long maxLatency,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        TraceService.TraceListResult result = traceService.listTraces(
                traceId, startDate, endDate, userId, sessionId, keyword, status,
                minLatency, maxLatency, page, size);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of(
                        "list", result.list,
                        "total", result.total,
                        "stats", result.stats
                )
        ));
    }

    /**
     * Trace 详情（按 traceId 返回所有事件，按 stepOrder 排序）
     */
    @GetMapping("/{traceId}")
    public ResponseEntity<Map<String, Object>> getTraceDetail(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String traceId) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        TraceService.TraceDetailResult detail = traceService.getTraceDetail(traceId);
        if (detail == null) {
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", null));
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of("summary", detail.summary, "events", detail.events)
        ));
    }
}
