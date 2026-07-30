package com.zyh.archivemind.feedback;

import com.zyh.archivemind.feedback.model.FeedbackRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户反馈采集接口。
 *
 * 统一响应格式：{code, message, data}
 *
 * 端点：
 * - POST /api/v1/feedback  接收前端用户对 AI 回答的反馈（点赞/点踩/部分正确/过时）
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 提交用户反馈。
     * 用户 ID 从 SecurityContext 的 AuthenticationPrincipal 中获取（与 TraceController 一致）。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody FeedbackRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", 401,
                    "message", "未登录",
                    "data", Map.of()
            ));
        }

        if (request.getTraceId() == null || request.getTraceId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "traceId 不能为空",
                    "data", Map.of()
            ));
        }
        if (request.getAction() == null || request.getAction().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "message", "action 不能为空",
                    "data", Map.of()
            ));
        }

        String userId = userDetails.getUsername();
        feedbackService.save(userId, request);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of()
        ));
    }
}
