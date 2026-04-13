package com.zyh.archivemind.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.service.ChatHandler;
import com.zyh.archivemind.utils.JwtUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ChatHandler chatHandler;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    
    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        // 使用 ConcurrentWebSocketSessionDecorator 包装，确保并发安全
        // 参数：sendTimeLimit=5000ms, bufferSizeLimit=65536 bytes
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 65536);
        sessions.put(userId, concurrentSession);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}", 
                    userId, session.getId(), session.getUri().getPath());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        // 使用 sessions map 中的包装后 session（ConcurrentWebSocketSessionDecorator），确保并发安全
        WebSocketSession wrappedSession = sessions.getOrDefault(userId, session);
        try {
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       userId, session.getId(), payload.length());
            
            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    
                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        // 处理停止指令
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", userId, session.getId());
                        chatHandler.stopResponse(userId, wrappedSession);
                        return;
                    }
                    
                    // 其他JSON消息当作普通消息处理
                    logger.debug("收到JSON格式的聊天消息，当作普通消息处理");
                } catch (Exception jsonParseError) {
                    // JSON解析失败，当作普通文本消息处理
                    logger.debug("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }
            
            // 普通聊天消息处理（保持向下兼容），使用包装后的 session 确保并发安全
            chatHandler.processMessage(userId, payload, wrappedSession);
            
        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", 
                        userId, session.getId(), e.getMessage(), e);
            sendErrorMessage(wrappedSession, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        // 清理可能残留的 builder 数据（防止内存泄漏）
        chatHandler.cleanupSession(session.getId());
        sessions.remove(userId);
        logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}", 
                    userId, session.getId(), status);
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];
        
        // 从JWT令牌中提取用户名
        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        if (username == null) {
            logger.warn("无法从JWT令牌中提取用户名，使用令牌作为用户ID: {}", jwtToken);
            return jwtToken;
        }
        
        logger.debug("从JWT令牌中提取的用户名: {}", username);
        return username;
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
} 