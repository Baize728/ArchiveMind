package com.zyh.archivemind.controller;

import com.zyh.archivemind.dto.GroupedSessionListDTO;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.dto.SessionDetailDTO;
import com.zyh.archivemind.exception.CustomException;
import com.zyh.archivemind.service.ConversationSessionService;
import com.zyh.archivemind.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sessions")
public class ConversationSessionController {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSessionController.class);

    private final ConversationSessionService conversationSessionService;
    private final JwtUtils jwtUtils;

    public ConversationSessionController(ConversationSessionService conversationSessionService,
                                         JwtUtils jwtUtils) {
        this.conversationSessionService = conversationSessionService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 创建新会话
     * POST /api/v1/sessions
     */
    @PostMapping
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token) {
        try {
            String userId = extractUserId(token);
            SessionDTO session = conversationSessionService.createSession(userId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "创建对话成功",
                    "data", Map.of(
                            "sessionId", session.getSessionId(),
                            "title", session.getTitle(),
                            "createdAt", session.getCreatedAt().toString()
                    )
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage(),
                    "data", ""
            ));
        } catch (Exception e) {
            logger.error("创建会话异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "创建对话失败，请稍后重试",
                    "data", ""
            ));
        }
    }

    /**
     * 获取会话列表（按时间分组）
     * GET /api/v1/sessions
     */
    @GetMapping
    public ResponseEntity<?> listSessions(@RequestHeader("Authorization") String token) {
        try {
            String userId = extractUserId(token);
            GroupedSessionListDTO sessions = conversationSessionService.listSessions(userId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "获取对话列表成功",
                    "data", sessions
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage(),
                    "data", ""
            ));
        } catch (Exception e) {
            logger.error("获取会话列表异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误",
                    "data", ""
            ));
        }
    }

    /**
     * 切换活跃会话
     * PUT /api/v1/sessions/{sessionId}/active
     */
    @PutMapping("/{sessionId}/active")
    public ResponseEntity<?> switchSession(@RequestHeader("Authorization") String token,
                                           @PathVariable String sessionId) {
        try {
            String userId = extractUserId(token);
            SessionDetailDTO detail = conversationSessionService.switchSession(userId, sessionId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "切换对话成功",
                    "data", detail
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage(),
                    "data", ""
            ));
        } catch (Exception e) {
            logger.error("切换会话异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误",
                    "data", ""
            ));
        }
    }

    /**
     * 删除会话
     * DELETE /api/v1/sessions/{sessionId}
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(@RequestHeader("Authorization") String token,
                                           @PathVariable String sessionId) {
        try {
            String userId = extractUserId(token);
            conversationSessionService.deleteSession(userId, sessionId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "删除对话成功",
                    "data", ""
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage(),
                    "data", ""
            ));
        } catch (Exception e) {
            logger.error("删除会话异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误",
                    "data", ""
            ));
        }
    }

    /**
     * 更新会话标题
     * PUT /api/v1/sessions/{sessionId}/title
     */
    @PutMapping("/{sessionId}/title")
    public ResponseEntity<?> updateTitle(@RequestHeader("Authorization") String token,
                                         @PathVariable String sessionId,
                                         @RequestBody Map<String, String> body) {
        try {
            String userId = extractUserId(token);
            String title = body.get("title");
            if (title == null || title.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "message", "标题不能为空",
                        "data", ""
                ));
            }
            conversationSessionService.updateTitle(userId, sessionId, title.trim());
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "更新标题成功",
                    "data", ""
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage(),
                    "data", ""
            ));
        } catch (Exception e) {
            logger.error("更新会话标题异常: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 500,
                    "message", "服务器内部错误",
                    "data", ""
            ));
        }
    }

    /**
     * 从 Authorization header 中提取用户ID（username）
     */
    private String extractUserId(String token) {
        String rawToken = token.replace("Bearer ", "");
        String username = jwtUtils.extractUsernameFromToken(rawToken);
        if (username == null || username.isEmpty()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return username;
    }
}
