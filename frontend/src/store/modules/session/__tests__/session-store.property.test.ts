/**
 * Feature: frontend-multi-session
 *
 * Property 1: 加载会话列表同步到 Store 状态
 * 对于任意后端返回的合法 GroupedSessionList 响应，调用 loadSessionList() 后，
 * Session Store 的 sessionList 应与 API 返回的数据完全一致。
 * 验证需求：2.4
 *
 * Property 2: 创建会话设置活跃 ID 并清空消息
 * 对于任意成功创建的会话，调用 createSession() 后，activeSessionId 应等于
 * 新创建会话的 sessionId，且 Chat Store 的 list 应为空数组。
 * 验证需求：2.5, 5.2
 *
 * Property 3: 切换会话同步活跃 ID 和消息历史
 * 对于任意合法的 sessionId，调用 switchSession(sessionId) 后，activeSessionId
 * 应等于该 sessionId，且 Chat Store 的 list 应与 API 返回的 messages 数组完全一致。
 * 验证需求：2.6, 5.1
 *
 * Property 4: 删除会话后列表不再包含该会话；若删除活跃会话则清空状态
 * 对于任意被删除的 sessionId，调用 deleteSession(sessionId) 后，sessionList 所有分组中
 * 不应再包含该 sessionId。若被删除的 sessionId 等于当前 activeSessionId，则 activeSessionId
 * 应为空字符串，且 Chat Store 的 list 应为空数组。
 * 验证需求：2.7, 2.8, 5.3
 */
import { describe, vi, beforeEach, expect } from 'vitest';
// @ts-ignore -- moduleResolution "node" cannot resolve exports map; runtime works fine via vitest/vite
import { test, fc } from '@fast-check/vitest';
import { createPinia, setActivePinia } from 'pinia';
import { ref } from 'vue';

// --- Arbitraries ---

const sessionArb = fc.record({
  sessionId: fc.uuid(),
  title: fc.string({ minLength: 1, maxLength: 50 }),
  createdAt: fc.integer({ min: 1577836800000, max: 1893456000000 }).map((ts: number) => new Date(ts).toISOString())
});

const messageArb = fc.record({
  role: fc.constantFrom('user' as const, 'assistant' as const),
  content: fc.string({ minLength: 1, maxLength: 200 }),
  timestamp: fc.integer({ min: 1577836800000, max: 1893456000000 }).map((ts: number) => new Date(ts).toISOString())
});

const sessionDetailArb = fc.record({
  sessionId: fc.uuid(),
  title: fc.string({ minLength: 1, maxLength: 50 }),
  createdAt: fc.integer({ min: 1577836800000, max: 1893456000000 }).map((ts: number) => new Date(ts).toISOString()),
  messages: fc.array(messageArb, { maxLength: 20 })
});

const groupedSessionListArb = fc.record({
  today: fc.array(sessionArb, { maxLength: 10 }),
  week: fc.array(sessionArb, { maxLength: 10 }),
  month: fc.array(sessionArb, { maxLength: 10 }),
  earlier: fc.dictionary(
    fc.stringMatching(/^\d{4}-\d{2}$/),
    fc.array(sessionArb, { minLength: 1, maxLength: 5 }),
    { maxKeys: 5 }
  )
});

// --- Mock setup ---

const mockFetchSessionList = vi.fn();
const mockFetchCreateSession = vi.fn();
const mockFetchSwitchSession = vi.fn();
const mockFetchDeleteSession = vi.fn();

vi.mock('@/service/api', () => ({
  fetchSessionList: (...args: any[]) => mockFetchSessionList(...args),
  fetchCreateSession: (...args: any[]) => mockFetchCreateSession(...args),
  fetchSwitchSession: (...args: any[]) => mockFetchSwitchSession(...args),
  fetchDeleteSession: (...args: any[]) => mockFetchDeleteSession(...args)
}));

// Mock the chat store to avoid its dependency chain (useAuthStore, useWebSocket, etc.)
const mockSetMessages = vi.fn();
const mockClearMessages = vi.fn();

vi.mock('@/store/modules/chat', () => ({
  useChatStore: () => ({
    setMessages: mockSetMessages,
    clearMessages: mockClearMessages,
    list: ref([])
  })
}));

// Also mock the relative import path used by session store
vi.mock('../chat', () => ({
  useChatStore: () => ({
    setMessages: mockSetMessages,
    clearMessages: mockClearMessages,
    list: ref([])
  })
}));

describe('Session Store - Property 1: loadSessionList syncs to store state', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'sessionList should exactly match the API response after loadSessionList()',
    async (mockData: Api.Chat.GroupedSessionList) => {
      // Re-create pinia for each property run to ensure isolation
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');

      // Arrange: mock API to return the generated data
      mockFetchSessionList.mockResolvedValue({
        error: null,
        data: mockData
      });

      const sessionStore = useSessionStore();

      // Act
      await sessionStore.loadSessionList();

      // Assert: store state matches API response exactly
      expect(sessionStore.sessionList.today).toEqual(mockData.today);
      expect(sessionStore.sessionList.week).toEqual(mockData.week);
      expect(sessionStore.sessionList.month).toEqual(mockData.month);
      expect(sessionStore.sessionList.earlier).toEqual(mockData.earlier);
      expect(sessionStore.loading).toBe(false);
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'sessionList should remain unchanged when API returns an error',
    async (_mockData: Api.Chat.GroupedSessionList) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');
      const sessionStore = useSessionStore();

      // Capture initial state
      const initialList = JSON.parse(JSON.stringify(sessionStore.sessionList));

      // Arrange: mock API to return error
      mockFetchSessionList.mockResolvedValue({
        error: { message: 'Network error' },
        data: null
      });

      // Act
      await sessionStore.loadSessionList();

      // Assert: store state unchanged on error
      expect(sessionStore.sessionList).toEqual(initialList);
      expect(sessionStore.loading).toBe(false);
    }
  );
});


describe('Session Store - Property 2: createSession sets activeSessionId and clears messages', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  test.prop(
    [
      sessionArb,
      groupedSessionListArb
    ],
    { numRuns: 100 }
  )(
    'activeSessionId should equal new session id and clearMessages should be called',
    async (newSession: Api.Chat.Session, listAfterCreate: Api.Chat.GroupedSessionList) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');

      // Arrange: mock createSession API success
      mockFetchCreateSession.mockResolvedValue({
        error: null,
        data: newSession
      });

      // mock loadSessionList (called internally after create)
      mockFetchSessionList.mockResolvedValue({
        error: null,
        data: listAfterCreate
      });

      const sessionStore = useSessionStore();

      // Act
      await sessionStore.createSession();

      // Assert: activeSessionId equals the newly created session's id
      expect(sessionStore.activeSessionId).toBe(newSession.sessionId);

      // Assert: clearMessages was called (Chat Store list should be empty)
      expect(mockClearMessages).toHaveBeenCalled();

      // Assert: creating flag is reset
      expect(sessionStore.creating).toBe(false);

      // Assert: session list was refreshed
      expect(sessionStore.sessionList).toEqual(listAfterCreate);
    }
  );

  test.prop([sessionArb], { numRuns: 100 })(
    'activeSessionId should remain unchanged when createSession API fails',
    async (_session: Api.Chat.Session) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');
      const sessionStore = useSessionStore();

      const initialActiveId = sessionStore.activeSessionId;

      // Arrange: mock createSession API failure
      mockFetchCreateSession.mockResolvedValue({
        error: { message: 'Server error' },
        data: null
      });

      // Act
      await sessionStore.createSession();

      // Assert: activeSessionId unchanged on error
      expect(sessionStore.activeSessionId).toBe(initialActiveId);

      // Assert: clearMessages NOT called on error
      expect(mockClearMessages).not.toHaveBeenCalled();

      // Assert: creating flag is reset
      expect(sessionStore.creating).toBe(false);
    }
  );
});


describe('Session Store - Property 3: switchSession syncs activeSessionId and message history', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  test.prop([sessionDetailArb], { numRuns: 100 })(
    'activeSessionId should equal the switched sessionId and setMessages should be called with returned messages',
    async (detail: Api.Chat.SessionDetail) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');

      // Arrange: mock switchSession API success
      mockFetchSwitchSession.mockResolvedValue({
        error: null,
        data: detail
      });

      const sessionStore = useSessionStore();

      // Act
      await sessionStore.switchSession(detail.sessionId);

      // Assert: activeSessionId equals the switched session's id
      expect(sessionStore.activeSessionId).toBe(detail.sessionId);

      // Assert: setMessages was called with the returned messages
      expect(mockSetMessages).toHaveBeenCalledWith(detail.messages);
    }
  );

  test.prop([fc.uuid()], { numRuns: 100 })(
    'activeSessionId should remain unchanged when switchSession API fails',
    async (sessionId: string) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');
      const sessionStore = useSessionStore();

      const initialActiveId = sessionStore.activeSessionId;

      // Arrange: mock switchSession API failure
      mockFetchSwitchSession.mockResolvedValue({
        error: { message: 'Network error' },
        data: null
      });

      // Act
      await sessionStore.switchSession(sessionId);

      // Assert: activeSessionId unchanged on error
      expect(sessionStore.activeSessionId).toBe(initialActiveId);

      // Assert: setMessages NOT called on error
      expect(mockSetMessages).not.toHaveBeenCalled();
    }
  );
});


/**
 * Feature: frontend-multi-session, Property 4: 删除会话后列表不再包含该会话；若删除活跃会话则清空状态
 * 对于任意被删除的 sessionId，调用 deleteSession(sessionId) 后，sessionList 所有分组中不应再包含该 sessionId。
 * 若被删除的 sessionId 等于当前 activeSessionId，则 activeSessionId 应为空字符串，且 Chat Store 的 list 应为空数组。
 * 验证需求：2.7, 2.8, 5.3
 */
describe('Session Store - Property 4: deleteSession removes session and clears state if active', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  /**
   * Helper: build a GroupedSessionList that does NOT contain the given sessionId.
   * This simulates the backend returning a refreshed list after deletion.
   */
  function removeSessionFromList(
    list: Api.Chat.GroupedSessionList,
    sessionId: string
  ): Api.Chat.GroupedSessionList {
    const filterArr = (arr: Api.Chat.Session[]) => arr.filter(s => s.sessionId !== sessionId);
    const filteredEarlier: Record<string, Api.Chat.Session[]> = {};
    for (const [key, arr] of Object.entries(list.earlier)) {
      const filtered = filterArr(arr);
      if (filtered.length > 0) {
        filteredEarlier[key] = filtered;
      }
    }
    return {
      today: filterArr(list.today),
      week: filterArr(list.week),
      month: filterArr(list.month),
      earlier: filteredEarlier
    };
  }

  /** Helper: collect all sessionIds from a GroupedSessionList */
  function allSessionIds(list: Api.Chat.GroupedSessionList): string[] {
    const ids: string[] = [];
    for (const s of list.today) ids.push(s.sessionId);
    for (const s of list.week) ids.push(s.sessionId);
    for (const s of list.month) ids.push(s.sessionId);
    for (const arr of Object.values(list.earlier)) {
      for (const s of arr) ids.push(s.sessionId);
    }
    return ids;
  }

  test.prop(
    [groupedSessionListArb, fc.boolean()],
    { numRuns: 100 }
  )(
    'deleted session should not appear in sessionList; if active, activeSessionId and messages should be cleared',
    async (initialList: Api.Chat.GroupedSessionList, deleteActiveSession: boolean) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');
      const sessionStore = useSessionStore();

      // Collect all session ids from the generated list
      const ids = allSessionIds(initialList);

      // If the list is empty, skip this run — nothing to delete
      if (ids.length === 0) return;

      // Pick a session to delete (first one from the list)
      const targetId = ids[0];

      // Seed the store with the initial list
      mockFetchSessionList.mockResolvedValueOnce({
        error: null,
        data: initialList
      });
      await sessionStore.loadSessionList();

      // Optionally set the target as the active session
      if (deleteActiveSession) {
        sessionStore.activeSessionId = targetId;
      } else {
        // Set a different active session (or empty if only one session)
        sessionStore.activeSessionId = ids.length > 1 ? ids[1] : '';
      }

      vi.clearAllMocks();

      // Arrange: mock delete API success
      mockFetchDeleteSession.mockResolvedValue({ error: null, data: null });

      // Arrange: mock the refreshed list (without the deleted session)
      const listAfterDelete = removeSessionFromList(initialList, targetId);
      mockFetchSessionList.mockResolvedValue({
        error: null,
        data: listAfterDelete
      });

      const activeIdBefore = sessionStore.activeSessionId;

      // Act
      await sessionStore.deleteSession(targetId);

      // Assert 1: the deleted session should not appear in any group
      const remainingIds = allSessionIds(sessionStore.sessionList);
      expect(remainingIds).not.toContain(targetId);

      // Assert 2: if the deleted session was the active one, state should be cleared
      if (deleteActiveSession) {
        expect(sessionStore.activeSessionId).toBe('');
        expect(mockClearMessages).toHaveBeenCalled();
      } else {
        // Active session should remain unchanged
        expect(sessionStore.activeSessionId).toBe(activeIdBefore);
        expect(mockClearMessages).not.toHaveBeenCalled();
      }
    }
  );

  test.prop([groupedSessionListArb, fc.uuid()], { numRuns: 100 })(
    'sessionList should remain unchanged when deleteSession API fails',
    async (initialList: Api.Chat.GroupedSessionList, targetId: string) => {
      setActivePinia(createPinia());

      const { useSessionStore } = await import('../index');
      const sessionStore = useSessionStore();

      // Seed the store with the initial list
      mockFetchSessionList.mockResolvedValueOnce({
        error: null,
        data: initialList
      });
      await sessionStore.loadSessionList();

      // Set the target as active
      sessionStore.activeSessionId = targetId;

      vi.clearAllMocks();

      // Arrange: mock delete API failure
      mockFetchDeleteSession.mockResolvedValue({
        error: { message: 'Server error' },
        data: null
      });

      // Act
      await sessionStore.deleteSession(targetId);

      // Assert: session list unchanged on error
      expect(sessionStore.sessionList).toEqual(initialList);

      // Assert: activeSessionId unchanged on error
      expect(sessionStore.activeSessionId).toBe(targetId);

      // Assert: clearMessages NOT called on error
      expect(mockClearMessages).not.toHaveBeenCalled();
    }
  );
});
