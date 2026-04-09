package com.zyh.archivemind.controller;

import com.zyh.archivemind.dto.GroupedSessionListDTO;
import com.zyh.archivemind.dto.MessageDTO;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.dto.SessionDetailDTO;
import com.zyh.archivemind.exception.CustomException;
import com.zyh.archivemind.service.ConversationSessionService;
import com.zyh.archivemind.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Controller 层单元测试 - ConversationSessionController
 *
 * 使用 Mockito 直接测试 Controller 方法，mock ConversationSessionService 和 JwtUtils。
 * 覆盖请求路由、响应格式和错误场景。
 *
 * Validates: Requirements 1.5, 2.5, 3.4, 5.4, 5.5
 */
@ExtendWith(MockitoExtension.class)
class ConversationSessionControllerTest {

    @Mock
    private ConversationSessionService conversationSessionService;

    @Mock
    private JwtUtils jwtUtils;

    private ConversationSessionController controller;

    private static final String VALID_TOKEN = "Bearer valid-jwt-token";
    private static final String USER_ID = "testUser";

    @BeforeEach
    void setUp() {
        controller = new ConversationSessionController(conversationSessionService, jwtUtils);
    }

    // ========== POST /api/v1/sessions - 创建新会话 ==========

    @Nested
    @DisplayName("POST /api/v1/sessions - 创建新会话")
    class CreateSessionTests {

        @Test
        @DisplayName("创建成功应返回 200 和会话信息")
        void createSession_success_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            LocalDateTime now = LocalDateTime.now();
            SessionDTO session = new SessionDTO("session-123", "新对话", now);
            when(conversationSessionService.createSession(USER_ID)).thenReturn(session);

            ResponseEntity<?> response = controller.createSession(VALID_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            assertThat(body.get("message")).isEqualTo("创建对话成功");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertThat(data.get("sessionId")).isEqualTo("session-123");
            assertThat(data.get("title")).isEqualTo("新对话");
            assertThat(data.get("createdAt")).isEqualTo(now.toString());
        }

        @Test
        @DisplayName("创建失败（服务异常）应返回 500 (Req 1.5)")
        void createSession_serviceException_returns500() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            when(conversationSessionService.createSession(USER_ID))
                    .thenThrow(new RuntimeException("Redis connection failed"));

            ResponseEntity<?> response = controller.createSession(VALID_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(500);
            assertThat(body.get("message")).isEqualTo("创建对话失败，请稍后重试");
        }
    }

    // ========== GET /api/v1/sessions - 获取会话列表 ==========

    @Nested
    @DisplayName("GET /api/v1/sessions - 获取会话列表")
    class ListSessionsTests {

        @Test
        @DisplayName("获取列表成功应返回 200 和分组数据")
        void listSessions_success_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            SessionDTO s1 = new SessionDTO("s1", "对话1", LocalDateTime.now());
            GroupedSessionListDTO grouped = new GroupedSessionListDTO(
                    List.of(s1), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap());
            when(conversationSessionService.listSessions(USER_ID)).thenReturn(grouped);

            ResponseEntity<?> response = controller.listSessions(VALID_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            assertThat(body.get("message")).isEqualTo("获取对话列表成功");
            assertThat(body.get("data")).isEqualTo(grouped);
        }

        @Test
        @DisplayName("无历史对话时应返回 200 和空列表 (Req 2.4)")
        void listSessions_empty_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            GroupedSessionListDTO empty = new GroupedSessionListDTO(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap());
            when(conversationSessionService.listSessions(USER_ID)).thenReturn(empty);

            ResponseEntity<?> response = controller.listSessions(VALID_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            GroupedSessionListDTO data = (GroupedSessionListDTO) body.get("data");
            assertThat(data.getToday()).isEmpty();
            assertThat(data.getWeek()).isEmpty();
            assertThat(data.getMonth()).isEmpty();
            assertThat(data.getEarlier()).isEmpty();
        }
    }

    // ========== PUT /api/v1/sessions/{sessionId}/active - 切换会话 ==========

    @Nested
    @DisplayName("PUT /api/v1/sessions/{sessionId}/active - 切换会话")
    class SwitchSessionTests {

        @Test
        @DisplayName("切换成功应返回 200 和会话详情")
        void switchSession_success_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            LocalDateTime now = LocalDateTime.now();
            List<MessageDTO> messages = List.of(MessageDTO.builder().role("user").content("hello").timestamp("2025-01-01T10:00:00").build());
            SessionDetailDTO detail = new SessionDetailDTO("s1", "对话1", now, messages);
            when(conversationSessionService.switchSession(USER_ID, "s1")).thenReturn(detail);

            ResponseEntity<?> response = controller.switchSession(VALID_TOKEN, "s1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            assertThat(body.get("message")).isEqualTo("切换对话成功");
            assertThat(body.get("data")).isEqualTo(detail);
        }

        @Test
        @DisplayName("切换不存在的会话应返回 404 (Req 3.4)")
        void switchSession_notFound_returns404() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            when(conversationSessionService.switchSession(USER_ID, "nonexistent"))
                    .thenThrow(new CustomException("对话不存在或已过期", HttpStatus.NOT_FOUND));

            ResponseEntity<?> response = controller.switchSession(VALID_TOKEN, "nonexistent");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(404);
            assertThat(body.get("message")).isEqualTo("对话不存在或已过期");
        }

        @Test
        @DisplayName("切换他人会话应返回 403")
        void switchSession_forbidden_returns403() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            when(conversationSessionService.switchSession(USER_ID, "other-session"))
                    .thenThrow(new CustomException("无权操作该对话", HttpStatus.FORBIDDEN));

            ResponseEntity<?> response = controller.switchSession(VALID_TOKEN, "other-session");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(403);
            assertThat(body.get("message")).isEqualTo("无权操作该对话");
        }
    }

    // ========== DELETE /api/v1/sessions/{sessionId} - 删除会话 ==========

    @Nested
    @DisplayName("DELETE /api/v1/sessions/{sessionId} - 删除会话")
    class DeleteSessionTests {

        @Test
        @DisplayName("删除成功应返回 200")
        void deleteSession_success_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            doNothing().when(conversationSessionService).deleteSession(USER_ID, "s1");

            ResponseEntity<?> response = controller.deleteSession(VALID_TOKEN, "s1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            assertThat(body.get("message")).isEqualTo("删除对话成功");
            verify(conversationSessionService).deleteSession(USER_ID, "s1");
        }

        @Test
        @DisplayName("删除不存在的会话应返回 404 (Req 5.5)")
        void deleteSession_notFound_returns404() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            doThrow(new CustomException("对话不存在", HttpStatus.NOT_FOUND))
                    .when(conversationSessionService).deleteSession(USER_ID, "nonexistent");

            ResponseEntity<?> response = controller.deleteSession(VALID_TOKEN, "nonexistent");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(404);
            assertThat(body.get("message")).isEqualTo("对话不存在");
        }

        @Test
        @DisplayName("删除他人会话应返回 403 (Req 5.4)")
        void deleteSession_forbidden_returns403() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            doThrow(new CustomException("无权删除该对话", HttpStatus.FORBIDDEN))
                    .when(conversationSessionService).deleteSession(USER_ID, "other-session");

            ResponseEntity<?> response = controller.deleteSession(VALID_TOKEN, "other-session");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(403);
            assertThat(body.get("message")).isEqualTo("无权删除该对话");
        }
    }

    // ========== PUT /api/v1/sessions/{sessionId}/title - 更新标题 ==========

    @Nested
    @DisplayName("PUT /api/v1/sessions/{sessionId}/title - 更新标题")
    class UpdateTitleTests {

        @Test
        @DisplayName("更新标题成功应返回 200")
        void updateTitle_success_returns200() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);
            doNothing().when(conversationSessionService).updateTitle(USER_ID, "s1", "新标题");

            ResponseEntity<?> response = controller.updateTitle(VALID_TOKEN, "s1",
                    Map.of("title", "新标题"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(200);
            assertThat(body.get("message")).isEqualTo("更新标题成功");
            verify(conversationSessionService).updateTitle(USER_ID, "s1", "新标题");
        }

        @Test
        @DisplayName("空标题应返回 400")
        void updateTitle_emptyTitle_returns400() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);

            ResponseEntity<?> response = controller.updateTitle(VALID_TOKEN, "s1",
                    Map.of("title", ""));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(400);
            assertThat(body.get("message")).isEqualTo("标题不能为空");
            verify(conversationSessionService, never()).updateTitle(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("缺少 title 字段应返回 400")
        void updateTitle_missingTitle_returns400() {
            when(jwtUtils.extractUsernameFromToken("valid-jwt-token")).thenReturn(USER_ID);

            ResponseEntity<?> response = controller.updateTitle(VALID_TOKEN, "s1",
                    Map.of("other", "value"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(400);
            assertThat(body.get("message")).isEqualTo("标题不能为空");
        }
    }

    // ========== 认证失败 - 401 ==========

    @Nested
    @DisplayName("认证失败 - 无效 Token 返回 401 (Req 2.5)")
    class AuthenticationTests {

        @Test
        @DisplayName("无效 token 创建会话应返回 401")
        void createSession_invalidToken_returns401() {
            when(jwtUtils.extractUsernameFromToken("invalid-token")).thenReturn(null);

            ResponseEntity<?> response = controller.createSession("Bearer invalid-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(401);
            assertThat(body.get("message")).isEqualTo("无效的token");
        }

        @Test
        @DisplayName("无效 token 获取列表应返回 401")
        void listSessions_invalidToken_returns401() {
            when(jwtUtils.extractUsernameFromToken("bad-token")).thenReturn("");

            ResponseEntity<?> response = controller.listSessions("Bearer bad-token");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(401);
        }

        @Test
        @DisplayName("无效 token 切换会话应返回 401")
        void switchSession_invalidToken_returns401() {
            when(jwtUtils.extractUsernameFromToken("bad-token")).thenReturn(null);

            ResponseEntity<?> response = controller.switchSession("Bearer bad-token", "s1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(401);
        }

        @Test
        @DisplayName("无效 token 删除会话应返回 401")
        void deleteSession_invalidToken_returns401() {
            when(jwtUtils.extractUsernameFromToken("bad-token")).thenReturn(null);

            ResponseEntity<?> response = controller.deleteSession("Bearer bad-token", "s1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(401);
        }

        @Test
        @DisplayName("无效 token 更新标题应返回 401")
        void updateTitle_invalidToken_returns401() {
            when(jwtUtils.extractUsernameFromToken("bad-token")).thenReturn(null);

            ResponseEntity<?> response = controller.updateTitle("Bearer bad-token", "s1",
                    Map.of("title", "test"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("code")).isEqualTo(401);
        }
    }
}
