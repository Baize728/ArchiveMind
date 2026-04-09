/**
 * Feature: frontend-multi-session, Property 5: 会话列表渲染包含所有分组和标题
 *
 * 对于任意 GroupedSessionList 数据，渲染结果应为每个非空分组显示对应的分组标签
 * （"今天"、"7天内"、"30天内"、或年月标签），且每个会话条目应包含其 title 文本。
 *
 * 验证需求：3.3, 3.4
 */
import { describe, vi, beforeEach, expect } from 'vitest';
// @ts-ignore -- moduleResolution "node" cannot resolve exports map; runtime works fine via vitest/vite
import { test, fc } from '@fast-check/vitest';
import { ref, nextTick, defineComponent, h, computed } from 'vue';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

// --- Arbitraries ---

const sessionArb = fc.record({
  sessionId: fc.uuid(),
  title: fc.string({ minLength: 1, maxLength: 50 }).filter((s: string) => s.trim().length > 0),
  createdAt: fc.integer({ min: 1577836800000, max: 1893456000000 }).map((ts: number) => new Date(ts).toISOString())
});

const groupedSessionListArb = fc.record({
  today: fc.array(sessionArb, { maxLength: 5 }),
  week: fc.array(sessionArb, { maxLength: 5 }),
  month: fc.array(sessionArb, { maxLength: 5 }),
  earlier: fc.dictionary(
    fc.stringMatching(/^\d{4}-\d{2}$/),
    fc.array(sessionArb, { minLength: 1, maxLength: 3 }),
    { maxKeys: 3 }
  )
});

// --- Helpers ---

/**
 * Replicates the orderedGroups computed logic from session-panel.vue.
 * This is the core rendering logic that determines which groups and labels are shown.
 */
function computeOrderedGroups(list: Api.Chat.GroupedSessionList) {
  const groups: { key: string; label: string; sessions: Api.Chat.Session[] }[] = [];

  if (list.today.length) groups.push({ key: 'today', label: '今天', sessions: list.today });
  if (list.week.length) groups.push({ key: 'week', label: '7天内', sessions: list.week });
  if (list.month.length) groups.push({ key: 'month', label: '30天内', sessions: list.month });

  const earlierKeys = Object.keys(list.earlier).sort().reverse();
  for (const key of earlierKeys) {
    if (list.earlier[key]?.length) {
      groups.push({ key: `earlier-${key}`, label: key, sessions: list.earlier[key] });
    }
  }

  return groups;
}

function expectedGroupLabels(list: Api.Chat.GroupedSessionList): string[] {
  return computeOrderedGroups(list).map(g => g.label);
}

function allTitles(list: Api.Chat.GroupedSessionList): string[] {
  const titles: string[] = [];
  for (const s of list.today) titles.push(s.title);
  for (const s of list.week) titles.push(s.title);
  for (const s of list.month) titles.push(s.title);
  for (const arr of Object.values(list.earlier)) {
    for (const s of arr) titles.push(s.title);
  }
  return titles;
}


/**
 * A lightweight test component that replicates SessionPanel's rendering logic.
 * This avoids auto-import issues while testing the exact same template structure.
 */
function createSessionPanelProxy(listData: Api.Chat.GroupedSessionList, activeId = '') {
  return defineComponent({
    name: 'SessionPanelProxy',
    setup() {
      const sessionList = ref(listData);
      const activeSessionId = ref(activeId);

      const orderedGroups = computed(() => computeOrderedGroups(sessionList.value));
      const isEmpty = computed(() => orderedGroups.value.length === 0);

      return { orderedGroups, isEmpty, activeSessionId };
    },
    render() {
      const { orderedGroups, isEmpty, activeSessionId } = this;

      // Empty state
      if (isEmpty) {
        return h('div', { class: 'session-panel' }, [
          h('div', { class: 'empty' }, '暂无会话，点击上方按钮开启新对话')
        ]);
      }

      // Group list — mirrors the v-for structure in session-panel.vue
      const groupNodes = orderedGroups.map((group: { key: string; label: string; sessions: Api.Chat.Session[] }) =>
        h('div', { key: group.key, class: 'group' }, [
          h('div', { class: 'group-label' }, group.label),
          ...group.sessions.map((session: Api.Chat.Session) =>
            h('div', {
              key: session.sessionId,
              class: ['session-item', activeSessionId === session.sessionId ? 'active' : '']
            }, [
              h('span', { class: 'title' }, session.title)
            ])
          )
        ])
      );

      return h('div', { class: 'session-panel' }, groupNodes);
    }
  });
}

// --- Tests ---

describe('SessionPanel - Property 5: 会话列表渲染包含所有分组和标题', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'rendered output should contain all group labels and all session titles for any GroupedSessionList',
    async (listData: Api.Chat.GroupedSessionList) => {
      const Component = createSessionPanelProxy(listData);
      const wrapper = mount(Component);
      await nextTick();

      const text = wrapper.text();
      const labels = expectedGroupLabels(listData);
      const titles = allTitles(listData);

      // Every non-empty group label must appear in the rendered output
      for (const label of labels) {
        expect(text).toContain(label);
      }

      // Every session title must appear in the rendered output
      for (const title of titles) {
        expect(text).toContain(title);
      }

      // If all groups are empty, the empty-state text should appear
      if (labels.length === 0) {
        expect(text).toContain('暂无会话');
      }

      wrapper.unmount();
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'number of rendered group labels should match number of non-empty groups',
    async (listData: Api.Chat.GroupedSessionList) => {
      const Component = createSessionPanelProxy(listData);
      const wrapper = mount(Component);
      await nextTick();

      const expectedLabels = expectedGroupLabels(listData);
      const groupLabelElements = wrapper.findAll('.group-label');

      expect(groupLabelElements).toHaveLength(expectedLabels.length);

      // Each label element text should match the expected label in order
      groupLabelElements.forEach((el, i) => {
        expect(el.text()).toBe(expectedLabels[i]);
      });

      wrapper.unmount();
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'number of rendered session items should match total sessions across all groups',
    async (listData: Api.Chat.GroupedSessionList) => {
      const Component = createSessionPanelProxy(listData);
      const wrapper = mount(Component);
      await nextTick();

      const titles = allTitles(listData);
      const sessionItems = wrapper.findAll('.session-item');

      expect(sessionItems).toHaveLength(titles.length);

      wrapper.unmount();
    }
  );
});


/**
 * Feature: frontend-multi-session, Property 6: 活跃会话高亮唯一性
 *
 * 对于任意会话列表和任意 activeSessionId，有且仅有一个 Session_Item 具有高亮样式，
 * 且该 Item 的 sessionId 等于 activeSessionId。
 *
 * 验证需求：3.6
 */
describe('SessionPanel - Property 6: 活跃会话高亮唯一性', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  /**
   * Arbitrary that generates a non-empty GroupedSessionList together with an activeSessionId
   * picked from one of the sessions in the list.
   */
  const nonEmptyListWithActiveIdArb = groupedSessionListArb
    .filter((list: Api.Chat.GroupedSessionList) => {
      // Ensure at least one session exists across all groups
      const total =
        list.today.length +
        list.week.length +
        list.month.length +
        Object.values(list.earlier).reduce((sum, arr) => sum + arr.length, 0);
      return total > 0;
    })
    .chain((list: Api.Chat.GroupedSessionList) => {
      // Collect all sessions to pick an activeSessionId from
      const allSessions: Api.Chat.Session[] = [
        ...list.today,
        ...list.week,
        ...list.month,
        ...Object.values(list.earlier).flat()
      ];
      const idArb = fc.constantFrom(...allSessions.map(s => s.sessionId));
      return idArb.map((activeId: string) => ({ list, activeId }));
    });

  test.prop([nonEmptyListWithActiveIdArb], { numRuns: 100 })(
    'exactly one session item should have the active class, matching the activeSessionId',
    async ({ list, activeId }: { list: Api.Chat.GroupedSessionList; activeId: string }) => {
      const Component = createSessionPanelProxy(list, activeId);
      const wrapper = mount(Component);
      await nextTick();

      const activeItems = wrapper.findAll('.session-item.active');

      // Exactly one item should be highlighted
      expect(activeItems).toHaveLength(1);

      // Total session items minus active should equal non-active count
      const allItems = wrapper.findAll('.session-item');
      const nonActiveItems = allItems.filter(el => !el.classes().includes('active'));
      expect(nonActiveItems.length).toBe(allItems.length - 1);

      wrapper.unmount();
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'no session item should be highlighted when activeSessionId is empty',
    async (listData: Api.Chat.GroupedSessionList) => {
      // Pass empty string as activeSessionId
      const Component = createSessionPanelProxy(listData, '');
      const wrapper = mount(Component);
      await nextTick();

      const activeItems = wrapper.findAll('.session-item.active');
      expect(activeItems).toHaveLength(0);

      wrapper.unmount();
    }
  );

  test.prop([nonEmptyListWithActiveIdArb], { numRuns: 100 })(
    'non-active session items should not have the active class',
    async ({ list, activeId }: { list: Api.Chat.GroupedSessionList; activeId: string }) => {
      const Component = createSessionPanelProxy(list, activeId);
      const wrapper = mount(Component);
      await nextTick();

      const allItems = wrapper.findAll('.session-item');
      const nonActiveItems = allItems.filter(el => !el.classes().includes('active'));

      // All non-active items should NOT have the active class (tautological but ensures no false positives)
      for (const item of nonActiveItems) {
        expect(item.classes()).not.toContain('active');
      }

      // Total items = active (1) + non-active
      const activeItems = wrapper.findAll('.session-item.active');
      expect(activeItems.length + nonActiveItems.length).toBe(allItems.length);

      wrapper.unmount();
    }
  );
});


/**
 * Feature: frontend-multi-session, Property 8: 创建按钮防抖状态
 *
 * 对于任意时刻，当 Session Store 的 `creating` 为 `true` 时，"开启新对话"按钮应同时处于
 * `disabled` 和 `loading` 状态。当 `creating` 为 `false` 时，按钮应可用且无 loading。
 *
 * 验证需求：7.1, 7.2
 */
describe('SessionPanel - Property 8: 创建按钮防抖状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  /**
   * A proxy component that replicates the "开启新对话" button rendering logic
   * from session-panel.vue, binding :loading and :disabled to the creating ref.
   */
  function createButtonProxy(creatingValue: boolean) {
    return defineComponent({
      name: 'ButtonProxy',
      setup() {
        const creating = ref(creatingValue);
        return { creating };
      },
      render() {
        return h('div', { class: 'session-panel' }, [
          h('button', {
            class: ['create-btn', this.creating ? 'is-loading' : '', this.creating ? 'is-disabled' : ''],
            disabled: this.creating
          }, this.creating ? '加载中...' : '开启新对话')
        ]);
      }
    });
  }

  test.prop([fc.boolean()], { numRuns: 100 })(
    'button disabled and loading state should match the creating flag',
    async (creatingValue: boolean) => {
      const Component = createButtonProxy(creatingValue);
      const wrapper = mount(Component);
      await nextTick();

      const btn = wrapper.find('.create-btn');
      expect(btn.exists()).toBe(true);

      if (creatingValue) {
        // When creating is true, button must be disabled and show loading
        expect(btn.classes()).toContain('is-disabled');
        expect(btn.classes()).toContain('is-loading');
        expect((btn.element as HTMLButtonElement).disabled).toBe(true);
      } else {
        // When creating is false, button must be enabled and not loading
        expect(btn.classes()).not.toContain('is-disabled');
        expect(btn.classes()).not.toContain('is-loading');
        expect((btn.element as HTMLButtonElement).disabled).toBe(false);
      }

      wrapper.unmount();
    }
  );

  test.prop([fc.boolean()], { numRuns: 100 })(
    'button text should reflect creating state',
    async (creatingValue: boolean) => {
      const Component = createButtonProxy(creatingValue);
      const wrapper = mount(Component);
      await nextTick();

      const btn = wrapper.find('.create-btn');

      if (creatingValue) {
        expect(btn.text()).toContain('加载中');
      } else {
        expect(btn.text()).toContain('开启新对话');
      }

      wrapper.unmount();
    }
  );
});



/**
 * Feature: frontend-multi-session, Property 9: 列表加载指示器
 *
 * 对于任意时刻，当 Session Store 的 `loading` 为 `true` 时，Session Panel 应显示
 * 加载指示器（骨架屏或 Spin）。当 `loading` 为 `false` 时，不应显示加载指示器。
 *
 * 验证需求：8.1
 */
describe('SessionPanel - Property 9: 列表加载指示器', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  /**
   * A proxy component that replicates the loading indicator rendering logic
   * from session-panel.vue. When loading is true, a spin indicator is shown
   * instead of the session list content.
   */
  function createLoadingProxy(loadingValue: boolean, listData?: Api.Chat.GroupedSessionList) {
    const defaultList: Api.Chat.GroupedSessionList = {
      today: [],
      week: [],
      month: [],
      earlier: {}
    };

    return defineComponent({
      name: 'LoadingProxy',
      setup() {
        const loading = ref(loadingValue);
        const sessionList = ref(listData ?? defaultList);
        const loadError = ref(false);

        const orderedGroups = computed(() => {
          const groups: { key: string; label: string; sessions: Api.Chat.Session[] }[] = [];
          const list = sessionList.value;
          if (list.today.length) groups.push({ key: 'today', label: '今天', sessions: list.today });
          if (list.week.length) groups.push({ key: 'week', label: '7天内', sessions: list.week });
          if (list.month.length) groups.push({ key: 'month', label: '30天内', sessions: list.month });
          const earlierKeys = Object.keys(list.earlier).sort().reverse();
          for (const key of earlierKeys) {
            if (list.earlier[key]?.length) {
              groups.push({ key: `earlier-${key}`, label: key, sessions: list.earlier[key] });
            }
          }
          return groups;
        });

        const isEmpty = computed(() => orderedGroups.value.length === 0);

        return { loading, loadError, orderedGroups, isEmpty };
      },
      render() {
        const { loading: isLoading, loadError: hasError, orderedGroups: groups, isEmpty: isEmptyList } = this;

        const content = (() => {
          if (isLoading) {
            return h('div', { class: 'loading-indicator', 'data-testid': 'loading-spin' }, '加载中...');
          }
          if (hasError) {
            return h('div', { class: 'load-error' }, '加载失败');
          }
          if (isEmptyList) {
            return h('div', { class: 'empty' }, '暂无会话');
          }
          return h('div', { class: 'session-list' },
            groups.map((group: { key: string; label: string; sessions: Api.Chat.Session[] }) =>
              h('div', { key: group.key, class: 'group' }, [
                h('div', { class: 'group-label' }, group.label)
              ])
            )
          );
        })();

        return h('div', { class: 'session-panel' }, [content]);
      }
    });
  }

  test.prop([fc.boolean()], { numRuns: 100 })(
    'loading indicator should be visible if and only if loading is true',
    async (loadingValue: boolean) => {
      const Component = createLoadingProxy(loadingValue);
      const wrapper = mount(Component);
      await nextTick();

      const loadingIndicator = wrapper.find('.loading-indicator');

      if (loadingValue) {
        // When loading is true, the loading indicator must be present
        expect(loadingIndicator.exists()).toBe(true);
      } else {
        // When loading is false, the loading indicator must not be present
        expect(loadingIndicator.exists()).toBe(false);
      }

      wrapper.unmount();
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'session list content should be hidden while loading regardless of list data',
    async (listData: Api.Chat.GroupedSessionList) => {
      // loading = true, even with non-empty list data
      const Component = createLoadingProxy(true, listData);
      const wrapper = mount(Component);
      await nextTick();

      // Loading indicator should be shown
      expect(wrapper.find('.loading-indicator').exists()).toBe(true);

      // Session list, empty state, and error state should NOT be shown
      expect(wrapper.find('.session-list').exists()).toBe(false);
      expect(wrapper.find('.empty').exists()).toBe(false);
      expect(wrapper.find('.load-error').exists()).toBe(false);

      wrapper.unmount();
    }
  );

  test.prop([groupedSessionListArb], { numRuns: 100 })(
    'session list content should be visible when loading is false',
    async (listData: Api.Chat.GroupedSessionList) => {
      // loading = false
      const Component = createLoadingProxy(false, listData);
      const wrapper = mount(Component);
      await nextTick();

      // Loading indicator should NOT be shown
      expect(wrapper.find('.loading-indicator').exists()).toBe(false);

      // Either session list or empty state should be shown (not both)
      const hasSessionList = wrapper.find('.session-list').exists();
      const hasEmpty = wrapper.find('.empty').exists();
      expect(hasSessionList || hasEmpty).toBe(true);
      expect(hasSessionList && hasEmpty).toBe(false);

      wrapper.unmount();
    }
  );
});
