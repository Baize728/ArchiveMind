package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.dto.*;
import com.zyh.archivemind.entity.Message;
import com.zyh.archivemind.exception.CustomException;
import com.zyh.archivemind.repository.RedisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConversationSessionServiceImpl implements ConversationSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSessionServiceImpl.class);
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private static final int MAX_SESSIONS_PER_USER = 200;
    private static final String DEFAULT_TITLE = "新对话";
    private static final int TITLE_MAX_LENGTH = 20;

    private final RedisRepository redisRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationSessionServiceImpl(RedisRepository redisRepository,
                                          StringRedisTemplate stringRedisTemplate,
                                          ObjectMapper objectMapper) {
        this.redisRepository = redisRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // ========== 3.1 创建新会话 ==========

    @Override
    public SessionDTO createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        double score = now.toInstant(ZoneOffset.UTC).toEpochMilli();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sessionId", sessionId);
        meta.put("userId", userId);
        meta.put("title", DEFAULT_TITLE);
        meta.put("createdAt", now.toString());

        try {
            String metaJson = objectMapper.writeValueAsString(meta);
            redisRepository.createSessionAtomic(userId, sessionId, metaJson, score, SESSION_TTL);
            enforceSessionLimit(userId);
            logger.info("为用户 {} 创建新会话: {}", userId, sessionId);
            return new SessionDTO(sessionId, DEFAULT_TITLE, now);
        } catch (JsonProcessingException e) {
            logger.error("序列化会话元数据失败: {}", e.getMessage(), e);
            throw new CustomException("创建对话失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("创建会话失败: {}", e.getMessage(), e);
            throw new CustomException("创建对话失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void enforceSessionLimit(String userId) {
        Long count = stringRedisTemplate.opsForZSet().zCard("user:" + userId + ":sessions");
        if (count != null && count > MAX_SESSIONS_PER_USER) {
            // 移除最早的会话（分数最低的）
            Set<String> oldest = stringRedisTemplate.opsForZSet().range("user:" + userId + ":sessions", 0, count - MAX_SESSIONS_PER_USER - 1);
            if (oldest != null) {
                for (String oldSessionId : oldest) {
                    redisRepository.deleteSessionAtomic(userId, oldSessionId);
                    logger.info("用户 {} 会话数超限，清理最早会话: {}", userId, oldSessionId);
                }
            }
        }
    }

    // ========== 3.2 会话列表查询和时间分组 ==========

    @Override
    public GroupedSessionListDTO listSessions(String userId) {
        Set<String> sessionIds = redisRepository.getUserSessionIds(userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new GroupedSessionListDTO(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap());
        }

        List<SessionDTO> allSessions = new ArrayList<>();
        for (String sid : sessionIds) {
            SessionDTO dto = parseSessionMeta(sid);
            if (dto != null) {
                allSessions.add(dto);
            }
        }

        // sessionIds 已按时间降序（reverseRange），保持顺序
        LocalDate today = LocalDate.now();
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate thirtyDaysAgo = today.minusDays(30);
        DateTimeFormatter yearMonthFmt = DateTimeFormatter.ofPattern("yyyy-MM");

        List<SessionDTO> todayList = new ArrayList<>();
        List<SessionDTO> weekList = new ArrayList<>();
        List<SessionDTO> monthList = new ArrayList<>();
        Map<String, List<SessionDTO>> earlierMap = new LinkedHashMap<>();

        for (SessionDTO s : allSessions) {
            LocalDate created = s.getCreatedAt().toLocalDate();
            if (!created.isBefore(today)) {
                todayList.add(s);
            } else if (!created.isBefore(sevenDaysAgo)) {
                weekList.add(s);
            } else if (!created.isBefore(thirtyDaysAgo)) {
                monthList.add(s);
            } else {
                String ym = created.format(yearMonthFmt);
                earlierMap.computeIfAbsent(ym, k -> new ArrayList<>()).add(s);
            }
        }

        return new GroupedSessionListDTO(todayList, weekList, monthList, earlierMap);
    }

    private SessionDTO parseSessionMeta(String sessionId) {
        try {
            String metaJson = redisRepository.getSessionMeta(sessionId);
            if (metaJson == null) return null;
            Map<String, String> meta = objectMapper.readValue(metaJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            return new SessionDTO(
                    meta.get("sessionId"),
                    meta.get("title"),
                    LocalDateTime.parse(meta.get("createdAt"))
            );
        } catch (Exception e) {
            logger.warn("解析会话元数据失败, sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // ========== 3.3 会话切换 ==========

    @Override
    public SessionDetailDTO switchSession(String userId, String sessionId) {
        // 验证会话存在性和归属权
        String metaJson = redisRepository.getSessionMeta(sessionId);
        if (metaJson == null) {
            throw new CustomException("对话不存在或已过期", HttpStatus.NOT_FOUND);
        }

        try {
            Map<String, String> meta = objectMapper.readValue(metaJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            if (!userId.equals(meta.get("userId"))) {
                throw new CustomException("无权操作该对话", HttpStatus.FORBIDDEN);
            }

            // 设置为活跃会话
            redisRepository.setActiveSession(userId, sessionId);

            // 获取消息历史
            List<Message> messages = redisRepository.getConversationHistory(sessionId);
            List<MessageDTO> messageDTOs = messages.stream()
                    .map(m -> MessageDTO.builder()
                            .role(m.getRole())
                            .content(m.getContent())
                            .thinkingContent(m.getThinkingContent())
                            .build())
                    .collect(Collectors.toList());

            return new SessionDetailDTO(
                    meta.get("sessionId"),
                    meta.get("title"),
                    LocalDateTime.parse(meta.get("createdAt")),
                    messageDTOs
            );
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("切换会话失败: {}", e.getMessage(), e);
            throw new CustomException("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ========== 3.4 会话标题管理 ==========

    @Override
    public void autoGenerateTitle(String sessionId, String firstMessage) {
        if (firstMessage == null || firstMessage.isEmpty()) return;

        String title;
        if (firstMessage.length() > TITLE_MAX_LENGTH) {
            title = firstMessage.substring(0, TITLE_MAX_LENGTH) + "...";
        } else {
            title = firstMessage;
        }

        updateSessionMetaTitle(sessionId, title);
    }

    @Override
    public void updateTitle(String userId, String sessionId, String title) {
        String metaJson = redisRepository.getSessionMeta(sessionId);
        if (metaJson == null) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }

        try {
            Map<String, String> meta = objectMapper.readValue(metaJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            if (!userId.equals(meta.get("userId"))) {
                throw new CustomException("无权操作该对话", HttpStatus.FORBIDDEN);
            }
            meta.put("title", title);
            redisRepository.saveSessionMeta(sessionId, objectMapper.writeValueAsString(meta));
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("更新会话标题失败: {}", e.getMessage(), e);
            throw new CustomException("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void updateSessionMetaTitle(String sessionId, String title) {
        try {
            String metaJson = redisRepository.getSessionMeta(sessionId);
            if (metaJson == null) return;
            Map<String, String> meta = objectMapper.readValue(metaJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            meta.put("title", title);
            redisRepository.saveSessionMeta(sessionId, objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            logger.warn("自动更新会话标题失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    // ========== 3.5 会话删除 ==========

    @Override
    public void deleteSession(String userId, String sessionId) {
        String metaJson = redisRepository.getSessionMeta(sessionId);
        if (metaJson == null) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }

        try {
            Map<String, String> meta = objectMapper.readValue(metaJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            if (!userId.equals(meta.get("userId"))) {
                throw new CustomException("无权删除该对话", HttpStatus.FORBIDDEN);
            }

            redisRepository.deleteSessionAtomic(userId, sessionId);
            logger.info("用户 {} 删除会话: {}", userId, sessionId);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("删除会话失败: {}", e.getMessage(), e);
            throw new CustomException("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ========== 3.6 数据迁移（旧键兼容） ==========

    @Override
    public String getActiveSessionId(String userId) {
        // 1. 先检查新键
        String activeSessionId = redisRepository.getActiveSession(userId);
        if (activeSessionId != null) {
            return activeSessionId;
        }

        // 2. 回退检查旧键
        String oldConversationId = redisRepository.getCurrentConversationId(userId);
        if (oldConversationId == null) {
            return null;
        }

        // 3. Lua 脚本原子迁移
        try {
            migrateOldSession(userId, oldConversationId);
            logger.info("用户 {} 旧会话 {} 迁移成功", userId, oldConversationId);
            return oldConversationId;
        } catch (Exception e) {
            logger.error("迁移旧会话失败, userId={}, conversationId={}: {}", userId, oldConversationId, e.getMessage());
            // 迁移失败时保留旧键不删除，返回旧 conversationId 以保证可用性
            return oldConversationId;
        }
    }

    private void migrateOldSession(String userId, String conversationId) throws JsonProcessingException {
        LocalDateTime now = LocalDateTime.now();
        double score = now.toInstant(ZoneOffset.UTC).toEpochMilli();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sessionId", conversationId);
        meta.put("userId", userId);
        meta.put("title", "历史对话");
        meta.put("createdAt", now.toString());

        String metaJson = objectMapper.writeValueAsString(meta);
        long ttlSeconds = SESSION_TTL.getSeconds();

        String script =
            "local sessionsKey = KEYS[1]\n" +
            "local metaKey = KEYS[2]\n" +
            "local activeKey = KEYS[3]\n" +
            "local oldKey = KEYS[4]\n" +
            "local sessionId = ARGV[1]\n" +
            "local metaJson = ARGV[2]\n" +
            "local score = tonumber(ARGV[3])\n" +
            "local ttlSeconds = tonumber(ARGV[4])\n" +
            "redis.call('ZADD', sessionsKey, score, sessionId)\n" +
            "redis.call('SET', metaKey, metaJson)\n" +
            "redis.call('EXPIRE', metaKey, ttlSeconds)\n" +
            "redis.call('SET', activeKey, sessionId)\n" +
            "redis.call('EXPIRE', activeKey, ttlSeconds)\n" +
            "redis.call('DEL', oldKey)\n" +
            "return 1";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        stringRedisTemplate.execute(redisScript,
                Arrays.asList(
                        "user:" + userId + ":sessions",
                        "session:" + conversationId + ":meta",
                        "user:" + userId + ":active_session",
                        "user:" + userId + ":current_conversation"
                ),
                conversationId, metaJson, String.valueOf((long) score), String.valueOf(ttlSeconds));
    }

    // ========== 3.7 TTL 刷新 ==========

    @Override
    public void refreshSessionTTL(String userId, String sessionId) {
        try {
            redisRepository.refreshSessionKeys(sessionId, userId, SESSION_TTL);
        } catch (Exception e) {
            logger.warn("刷新会话 TTL 失败, sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
