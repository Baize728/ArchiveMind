package com.zyh.archivemind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zyh.archivemind.dto.GroupedSessionListDTO;
import com.zyh.archivemind.dto.MessageDTO;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.dto.SessionDetailDTO;
import com.zyh.archivemind.entity.Message;
import com.zyh.archivemind.repository.RedisRepository;
import net.jqwik.api.*;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Combinators;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature: new-conversation
 * Property-based tests for ConversationSessionService
 * Validates: Requirements 1.2, 1.3, 4.1, 4.2, 4.4, 5.1, 5.2, 7.1, 7.2, 7.3
 */
class ConversationSessionServicePropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Property 1: Session creation data round-trip
     * Validates: Requirements 1.2, 7.1, 7.2, 7.3
     */
    @Property(tries = 10)
    void sessionCreationDataRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("user:" + userId + ":sessions")).thenReturn(0L);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        SessionDTO result = service.createSession(userId);

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isNotNull().isNotEmpty();
        assertThat(result.getTitle()).isEqualTo("\u65b0\u5bf9\u8bdd");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metaJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(redisRepository).createSessionAtomic(
                userIdCaptor.capture(),
                sessionIdCaptor.capture(),
                metaJsonCaptor.capture(),
                scoreCaptor.capture(),
                ttlCaptor.capture()
        );

        assertThat(userIdCaptor.getValue()).isEqualTo(userId);
        assertThat(sessionIdCaptor.getValue()).isEqualTo(result.getSessionId());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofDays(30));
        assertThat(scoreCaptor.getValue()).isPositive();

        @SuppressWarnings("unchecked")
        Map<String, String> meta = objectMapper.readValue(metaJsonCaptor.getValue(), Map.class);

        assertThat(meta).containsKey("sessionId");
        assertThat(meta).containsKey("userId");
        assertThat(meta).containsKey("title");
        assertThat(meta).containsKey("createdAt");

        assertThat(meta.get("sessionId")).isEqualTo(result.getSessionId());
        assertThat(meta.get("userId")).isEqualTo(userId);
        assertThat(meta.get("title")).isEqualTo("\u65b0\u5bf9\u8bdd");

        LocalDateTime metaCreatedAt = LocalDateTime.parse(meta.get("createdAt"));
        assertThat(metaCreatedAt).isEqualTo(result.getCreatedAt());

        when(redisRepository.getConversationHistory(result.getSessionId()))
                .thenReturn(new java.util.ArrayList<>());
        var history = redisRepository.getConversationHistory(result.getSessionId());
        assertThat(history).isNotNull();

        var testMessages = java.util.List.of(
                new com.zyh.archivemind.entity.Message("user", "test message"));
        redisRepository.saveConversationHistory(result.getSessionId(), testMessages);
        verify(redisRepository).saveConversationHistory(eq(result.getSessionId()), eq(testMessages));

        when(redisRepository.getConversationHistory(result.getSessionId()))
                .thenReturn(testMessages);
        var readBack = redisRepository.getConversationHistory(result.getSessionId());
        assertThat(readBack).hasSize(1);
        assertThat(readBack.get(0).getRole()).isEqualTo("user");
        assertThat(readBack.get(0).getContent()).isEqualTo("test message");
    }

    /**
     * Property 8: Auto title generation truncation rule
     * Validates: Requirements 4.1, 4.2
     */
    @Property(tries = 10)
    void autoGenerateTitleTruncationRule(
            @ForAll @StringLength(min = 1, max = 200) String firstMessage
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = "test-session-" + UUID.randomUUID();

        Map<String, String> existingMeta = new LinkedHashMap<>();
        existingMeta.put("sessionId", sessionId);
        existingMeta.put("userId", "testUser");
        existingMeta.put("title", "\u65b0\u5bf9\u8bdd");
        existingMeta.put("createdAt", LocalDateTime.now().toString());
        String existingMetaJson = objectMapper.writeValueAsString(existingMeta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(existingMetaJson);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        service.autoGenerateTitle(sessionId, firstMessage);

        ArgumentCaptor<String> metaCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisRepository).saveSessionMeta(eq(sessionId), metaCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, String> savedMeta = objectMapper.readValue(metaCaptor.getValue(), Map.class);
        String savedTitle = savedMeta.get("title");

        if (firstMessage.length() > 20) {
            String expectedTitle = firstMessage.substring(0, 20) + "...";
            assertThat(savedTitle).isEqualTo(expectedTitle);
            assertThat(savedTitle).hasSize(23);
        } else {
            assertThat(savedTitle).isEqualTo(firstMessage);
            assertThat(savedTitle).hasSize(firstMessage.length());
        }
    }

    /**
     * Property 2: Creating session auto-sets active session
     * Validates: Requirements 1.3
     */
    @Property(tries = 10)
    void createSessionSetsActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId
    ) {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("user:" + userId + ":sessions")).thenReturn(0L);

        doAnswer(invocation -> {
            String capturedSessionId = invocation.getArgument(1);
            when(redisRepository.getActiveSession(userId)).thenReturn(capturedSessionId);
            return null;
        }).when(redisRepository).createSessionAtomic(
                eq(userId), anyString(), anyString(), anyDouble(), any(Duration.class));

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        SessionDTO result = service.createSession(userId);

        String activeSessionId = service.getActiveSessionId(userId);
        assertThat(activeSessionId).isNotNull();
        assertThat(activeSessionId).isEqualTo(result.getSessionId());

        verify(redisRepository).createSessionAtomic(
                eq(userId), eq(result.getSessionId()), anyString(), anyDouble(), eq(Duration.ofDays(30)));
    }

    /**
     * Property 9: Title update persistence round-trip
     * Validates: Requirements 4.4
     */
    @Property(tries = 10)
    void titleUpdatePersistenceRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId,
            @ForAll @StringLength(min = 1, max = 100) String newTitle
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = UUID.randomUUID().toString();

        Map<String, String> existingMeta = new LinkedHashMap<>();
        existingMeta.put("sessionId", sessionId);
        existingMeta.put("userId", userId);
        existingMeta.put("title", "\u65b0\u5bf9\u8bdd");
        existingMeta.put("createdAt", LocalDateTime.now().toString());
        String existingMetaJson = objectMapper.writeValueAsString(existingMeta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(existingMetaJson);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        service.updateTitle(userId, sessionId, newTitle);

        ArgumentCaptor<String> metaCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisRepository).saveSessionMeta(eq(sessionId), metaCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, String> savedMeta = objectMapper.readValue(metaCaptor.getValue(), Map.class);

        assertThat(savedMeta.get("title")).isEqualTo(newTitle);
        assertThat(savedMeta.get("sessionId")).isEqualTo(sessionId);
        assertThat(savedMeta.get("userId")).isEqualTo(userId);
        assertThat(savedMeta.get("createdAt")).isEqualTo(existingMeta.get("createdAt"));
    }

    /**
     * Property 10: Delete session removes all data
     * Validates: Requirements 5.1
     */
    @Property(tries = 10)
    void deleteSessionRemovesAllData(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = UUID.randomUUID().toString();

        Map<String, String> existingMeta = new LinkedHashMap<>();
        existingMeta.put("sessionId", sessionId);
        existingMeta.put("userId", userId);
        existingMeta.put("title", "test session");
        existingMeta.put("createdAt", LocalDateTime.now().toString());
        String existingMetaJson = objectMapper.writeValueAsString(existingMeta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(existingMetaJson);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        service.deleteSession(userId, sessionId);

        verify(redisRepository).deleteSessionAtomic(userId, sessionId);
    }

    /**
     * Property 11: Delete active session clears reference
     * Validates: Requirements 5.2
     */
    @Property(tries = 10)
    void deleteActiveSessionClearsReference(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = UUID.randomUUID().toString();

        Map<String, String> existingMeta = new LinkedHashMap<>();
        existingMeta.put("sessionId", sessionId);
        existingMeta.put("userId", userId);
        existingMeta.put("title", "test session");
        existingMeta.put("createdAt", LocalDateTime.now().toString());
        String existingMetaJson = objectMapper.writeValueAsString(existingMeta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(existingMetaJson);
        when(redisRepository.getActiveSession(userId)).thenReturn(sessionId);

        doAnswer(invocation -> {
            when(redisRepository.getActiveSession(userId)).thenReturn(null);
            return null;
        }).when(redisRepository).deleteSessionAtomic(userId, sessionId);

        when(redisRepository.getCurrentConversationId(userId)).thenReturn(null);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        assertThat(service.getActiveSessionId(userId)).isEqualTo(sessionId);

        service.deleteSession(userId, sessionId);

        String activeSessionId = service.getActiveSessionId(userId);
        assertThat(activeSessionId).isNull();

        verify(redisRepository).deleteSessionAtomic(userId, sessionId);
    }

    /**
     * Feature: new-conversation, Property 3: 会话列表按时间降序排�?
     * For any user with a set of sessions (each with distinct creation times),
     * listSessions should return sessions in each group ordered by createdAt descending.
     * Validates: Requirements 2.1
     */
    @Property(tries = 100)
    void sessionListOrderedByTimeDescending(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll @IntRange(min = 2, max = 15) int sessionCount
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        // Generate sessions with random but distinct creation times spread across time groups
        Random rng = new Random();
        LocalDateTime now = LocalDateTime.now();
        List<Map.Entry<String, LocalDateTime>> sessions = new ArrayList<>();
        for (int i = 0; i < sessionCount; i++) {
            String sessionId = UUID.randomUUID().toString();
            // Spread across: today (0d), week (1-6d), month (7-29d), earlier (30-365d)
            int daysAgo = rng.nextInt(366);
            int hoursOffset = rng.nextInt(24);
            int minutesOffset = rng.nextInt(60);
            LocalDateTime createdAt = now.minusDays(daysAgo).minusHours(hoursOffset).minusMinutes(minutesOffset);
            sessions.add(new AbstractMap.SimpleEntry<>(sessionId, createdAt));
        }

        // Sort descending by createdAt (simulating Redis reverseRange behavior)
        sessions.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // Build the ordered set of session IDs (as Redis would return them)
        Set<String> orderedIds = new LinkedHashSet<>();
        for (Map.Entry<String, LocalDateTime> entry : sessions) {
            orderedIds.add(entry.getKey());
        }
        when(redisRepository.getUserSessionIds(userId)).thenReturn(orderedIds);

        // Mock getSessionMeta for each session
        for (Map.Entry<String, LocalDateTime> entry : sessions) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("sessionId", entry.getKey());
            meta.put("userId", userId);
            meta.put("title", "Session " + entry.getKey().substring(0, 8));
            meta.put("createdAt", entry.getValue().toString());
            when(redisRepository.getSessionMeta(entry.getKey()))
                    .thenReturn(objectMapper.writeValueAsString(meta));
        }

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        GroupedSessionListDTO result = service.listSessions(userId);

        // Verify each group is in descending createdAt order
        assertDescendingOrder(result.getToday(), "today");
        assertDescendingOrder(result.getWeek(), "week");
        assertDescendingOrder(result.getMonth(), "month");
        if (result.getEarlier() != null) {
            for (Map.Entry<String, List<SessionDTO>> entry : result.getEarlier().entrySet()) {
                assertDescendingOrder(entry.getValue(), "earlier[" + entry.getKey() + "]");
            }
        }

        // Verify all sessions are accounted for (none lost during grouping)
        int totalReturned = result.getToday().size() + result.getWeek().size()
                + result.getMonth().size()
                + (result.getEarlier() == null ? 0 :
                   result.getEarlier().values().stream().mapToInt(List::size).sum());
        assertThat(totalReturned).isEqualTo(sessionCount);
    }

    /**
     * Feature: new-conversation, Property 4: 会话时间分组正确�?
     * For any session and its createdAt timestamp, the session should be assigned to the correct
     * time group: "today" if created today, "week" if 1-7 days ago, "month" if 7-30 days ago,
     * "earlier" (keyed by year-month) if more than 30 days ago. Each returned session object
     * should contain sessionId, title, and createdAt fields.
     * Validates: Requirements 2.2, 2.3
     */
    @Property(tries = 100)
    void sessionTimeGroupingCorrectness(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll("sessionWithTimestamp") List<Map.Entry<String, LocalDateTime>> sessions
    ) throws JsonProcessingException {
        Assume.that(sessions != null && !sessions.isEmpty());

        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        // Sort descending by createdAt (simulating Redis reverseRange behavior)
        sessions.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        Set<String> orderedIds = new LinkedHashSet<>();
        for (Map.Entry<String, LocalDateTime> entry : sessions) {
            orderedIds.add(entry.getKey());
        }
        when(redisRepository.getUserSessionIds(userId)).thenReturn(orderedIds);

        // Mock getSessionMeta for each session
        for (Map.Entry<String, LocalDateTime> entry : sessions) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("sessionId", entry.getKey());
            meta.put("userId", userId);
            meta.put("title", "Session " + entry.getKey().substring(0, 8));
            meta.put("createdAt", entry.getValue().toString());
            when(redisRepository.getSessionMeta(entry.getKey()))
                    .thenReturn(objectMapper.writeValueAsString(meta));
        }

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        GroupedSessionListDTO result = service.listSessions(userId);

        // Compute expected grouping boundaries
        LocalDate today = LocalDate.now();
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate thirtyDaysAgo = today.minusDays(30);
        java.time.format.DateTimeFormatter yearMonthFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");

        // Verify each session is in the correct group
        for (Map.Entry<String, LocalDateTime> entry : sessions) {
            String sessionId = entry.getKey();
            LocalDate createdDate = entry.getValue().toLocalDate();

            if (!createdDate.isBefore(today)) {
                // Should be in "today" group
                assertThat(result.getToday())
                        .as("Session %s (created %s) should be in 'today' group", sessionId, createdDate)
                        .anyMatch(s -> s.getSessionId().equals(sessionId));
            } else if (!createdDate.isBefore(sevenDaysAgo)) {
                // Should be in "week" group
                assertThat(result.getWeek())
                        .as("Session %s (created %s) should be in 'week' group", sessionId, createdDate)
                        .anyMatch(s -> s.getSessionId().equals(sessionId));
            } else if (!createdDate.isBefore(thirtyDaysAgo)) {
                // Should be in "month" group
                assertThat(result.getMonth())
                        .as("Session %s (created %s) should be in 'month' group", sessionId, createdDate)
                        .anyMatch(s -> s.getSessionId().equals(sessionId));
            } else {
                // Should be in "earlier" group, keyed by year-month
                String expectedKey = createdDate.format(yearMonthFmt);
                assertThat(result.getEarlier())
                        .as("Earlier map should contain key '%s' for session %s", expectedKey, sessionId)
                        .containsKey(expectedKey);
                assertThat(result.getEarlier().get(expectedKey))
                        .as("Session %s should be in earlier['%s']", sessionId, expectedKey)
                        .anyMatch(s -> s.getSessionId().equals(sessionId));
            }
        }

        // Verify every returned session has sessionId, title, and createdAt
        List<SessionDTO> allReturned = new ArrayList<>();
        allReturned.addAll(result.getToday());
        allReturned.addAll(result.getWeek());
        allReturned.addAll(result.getMonth());
        if (result.getEarlier() != null) {
            result.getEarlier().values().forEach(allReturned::addAll);
        }

        for (SessionDTO dto : allReturned) {
            assertThat(dto.getSessionId()).as("sessionId should not be null").isNotNull();
            assertThat(dto.getTitle()).as("title should not be null").isNotNull();
            assertThat(dto.getCreatedAt()).as("createdAt should not be null").isNotNull();
        }

        // Verify total count matches (no sessions lost or duplicated)
        assertThat(allReturned).hasSize(sessions.size());
    }

    /**
     * Feature: new-conversation, Property 5: 切换会话设置活跃会话
     * For any user and any session they own, after calling switchSession,
     * getActiveSessionId should return the switched-to session ID.
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    void switchSessionSetsActiveSession(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now();

        // Build session meta owned by this user
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sessionId", sessionId);
        meta.put("userId", userId);
        meta.put("title", "Test Session");
        meta.put("createdAt", createdAt.toString());
        String metaJson = objectMapper.writeValueAsString(meta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(metaJson);
        when(redisRepository.getConversationHistory(sessionId)).thenReturn(Collections.emptyList());

        // Simulate setActiveSession storing the value so getActiveSession returns it
        doAnswer(invocation -> {
            String uid = invocation.getArgument(0);
            String sid = invocation.getArgument(1);
            when(redisRepository.getActiveSession(uid)).thenReturn(sid);
            return null;
        }).when(redisRepository).setActiveSession(anyString(), anyString());

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        // Perform switch
        SessionDetailDTO result = service.switchSession(userId, sessionId);

        // Verify switchSession returned correct data
        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isEqualTo(sessionId);

        // Verify active session is now the switched-to session
        String activeSessionId = service.getActiveSessionId(userId);
        assertThat(activeSessionId).isEqualTo(sessionId);

        // Verify setActiveSession was called with correct arguments
        verify(redisRepository).setActiveSession(userId, sessionId);
    }

    /**
     * Feature: new-conversation, Property 6: 切换会话返回消息历史往�?
     * For any session, store a set of messages, then switch to that session via switchSession.
     * The returned message list should match the stored messages in role and content, in order.
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    void switchSessionReturnsMessageHistoryRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String userId,
            @ForAll("messageList") List<Message> messages
    ) throws JsonProcessingException {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        String sessionId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now();

        // Build session meta owned by this user
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sessionId", sessionId);
        meta.put("userId", userId);
        meta.put("title", "Test Session");
        meta.put("createdAt", createdAt.toString());
        String metaJson = objectMapper.writeValueAsString(meta);

        when(redisRepository.getSessionMeta(sessionId)).thenReturn(metaJson);
        when(redisRepository.getConversationHistory(sessionId)).thenReturn(messages);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        SessionDetailDTO result = service.switchSession(userId, sessionId);

        // Verify returned messages match stored messages in count, role, and content
        assertThat(result.getMessages()).hasSize(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MessageDTO returned = result.getMessages().get(i);
            Message original = messages.get(i);
            assertThat(returned.getRole())
                    .as("Message[%d] role should match", i)
                    .isEqualTo(original.getRole());
            assertThat(returned.getContent())
                    .as("Message[%d] content should match", i)
                    .isEqualTo(original.getContent());
        }

        // Verify session metadata is correct in the response
        assertThat(result.getSessionId()).isEqualTo(sessionId);
        assertThat(result.getTitle()).isEqualTo("Test Session");
        assertThat(result.getCreatedAt()).isEqualTo(createdAt);
    }

    @Provide
    Arbitrary<List<Message>> messageList() {
        Arbitrary<String> roles = Arbitraries.of("user", "assistant");
        Arbitrary<String> contents = Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(200)
                .alpha();
        Arbitrary<Message> singleMessage = Combinators.combine(roles, contents)
                .as(Message::new);
        return singleMessage.list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<Map.Entry<String, LocalDateTime>>> sessionWithTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        Arbitrary<Map.Entry<String, LocalDateTime>> singleSession =
                Arbitraries.integers().between(0, 400).flatMap(daysAgo ->
                        Arbitraries.integers().between(0, 23).flatMap(hours ->
                                Arbitraries.integers().between(0, 59).map(minutes -> {
                                    String sessionId = UUID.randomUUID().toString();
                                    LocalDateTime createdAt = now.minusDays(daysAgo).minusHours(hours).minusMinutes(minutes);
                                    return (Map.Entry<String, LocalDateTime>) new AbstractMap.SimpleEntry<>(sessionId, createdAt);
                                })
                        )
                );
        return singleSession.list().ofMinSize(1).ofMaxSize(20);
    }

    /**
     * Feature: new-conversation, Property 13: TTL 管理
     * For any newly created session, createSessionAtomic is called with a 30-day TTL.
     * When refreshSessionTTL is called, refreshSessionKeys is invoked with the correct
     * sessionId, userId, and 30-day Duration, simulating TTL refresh after a new message.
     * Validates: Requirements 7.4, 7.5
     */
    @Property(tries = 100)
    void ttlManagement(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String userId
    ) {
        RedisRepository redisRepository = mock(RedisRepository.class);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard("user:" + userId + ":sessions")).thenReturn(0L);

        ConversationSessionServiceImpl service = new ConversationSessionServiceImpl(
                redisRepository, redisTemplate, objectMapper);

        // --- Part 1: Verify creation sets 30-day TTL ---
        SessionDTO created = service.createSession(userId);

        ArgumentCaptor<Duration> createTtlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisRepository).createSessionAtomic(
                eq(userId),
                eq(created.getSessionId()),
                anyString(),
                anyDouble(),
                createTtlCaptor.capture()
        );
        assertThat(createTtlCaptor.getValue()).isEqualTo(Duration.ofDays(30));

        // --- Part 2: Verify refreshSessionTTL delegates with 30-day TTL ---
        service.refreshSessionTTL(userId, created.getSessionId());

        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> refreshTtlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(redisRepository).refreshSessionKeys(
                sessionIdCaptor.capture(),
                userIdCaptor.capture(),
                refreshTtlCaptor.capture()
        );

        assertThat(sessionIdCaptor.getValue()).isEqualTo(created.getSessionId());
        assertThat(userIdCaptor.getValue()).isEqualTo(userId);
        assertThat(refreshTtlCaptor.getValue()).isEqualTo(Duration.ofDays(30));
    }

    private void assertDescendingOrder(List<SessionDTO> sessions, String groupName) {
        if (sessions == null || sessions.size() <= 1) return;
        for (int i = 0; i < sessions.size() - 1; i++) {
            assertThat(sessions.get(i).getCreatedAt())
                    .as("Group '%s': session[%d].createdAt should be >= session[%d].createdAt", groupName, i, i + 1)
                    .isAfterOrEqualTo(sessions.get(i + 1).getCreatedAt());
        }
    }
}
