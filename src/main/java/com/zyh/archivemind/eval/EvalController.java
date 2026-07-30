package com.zyh.archivemind.eval;

import com.zyh.archivemind.eval.model.EvalReport;
import com.zyh.archivemind.eval.model.EvalRequest;
import com.zyh.archivemind.eval.model.EvalSampleRow;
import com.zyh.archivemind.eval.model.EvalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测 + 标注 API。
 *
 * 统一响应格式：{code, message, data}
 * - code=200 表示成功
 * - 异步任务启动用 HTTP 202 Accepted + code=200
 *
 * 端点：
 * - POST /api/v1/eval           触发评测（异步，返回 taskId）
 * - GET  /api/v1/eval/tasks/{taskId}  查任务状态
 * - POST /api/v1/eval/samples   保存标注
 * - GET  /api/v1/eval/samples   查标注列表
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private static final Logger logger = LoggerFactory.getLogger(EvalController.class);

    private final EvaluationService evaluationService;
    private final EvalSampleService evalSampleService;

    public EvalController(EvaluationService evaluationService, EvalSampleService evalSampleService) {
        this.evaluationService = evaluationService;
        this.evalSampleService = evalSampleService;
    }

    /**
     * 触发评测（异步），返回 taskId。
     * HTTP 202 Accepted + code=200（前端 isBackendSuccess 认 code=200 为成功）。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> evaluate(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody EvalRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        if (request.getUserId() == null) {
            request.setUserId(userDetails.getUsername());
        }

        String taskId = evaluationService.createTask(request);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", "RUNNING");

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "code", 200,
                "message", "accepted",
                "data", data
        ));
    }

    /**
     * 查任务状态。
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String taskId) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        EvalTask task = evaluationService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.ok(Map.of(
                    "code", 404,
                    "message", "task not found",
                    "data", Map.of()
            ));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("status", task.getStatus().name());
        data.put("progress", Map.of(
                "total", task.getTotal().get(),
                "done", task.getDone().get()
        ));

        if (task.getStatus() == EvalTask.Status.COMPLETED) {
            data.put("result", task.getResult());
        } else if (task.getStatus() == EvalTask.Status.FAILED) {
            data.put("errorMessage", task.getErrorMessage());
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", data
        ));
    }

    /**
     * 保存标注（upsert）。
     */
    @PostMapping("/samples")
    public ResponseEntity<Map<String, Object>> saveSample(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody EvalSampleRow row) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        if (row.getLabeledBy() == null) {
            row.setLabeledBy(userDetails.getUsername());
        }
        if (row.getSource() == null) {
            row.setSource("MANUAL");
        }

        evalSampleService.saveSample(row);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of()
        ));
    }

    /**
     * 查标注列表。
     *
     * @param source 数据来源（MANUAL / BADCASE_BACKFLOW）
     * @param status pending（待标注）/ labeled（已标注）
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    @GetMapping("/samples")
    public ResponseEntity<Map<String, Object>> listSamples(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "MANUAL") String source,
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }

        boolean pending = "pending".equalsIgnoreCase(status);
        int offset = (page - 1) * size;

        List<EvalSampleRow> list = evalSampleService.findBySource(source, pending, offset, size);
        int total = evalSampleService.countBySourceAndStatus(source, pending);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of(
                        "list", list,
                        "total", total,
                        "page", page,
                        "size", size
                )
        ));
    }
}
