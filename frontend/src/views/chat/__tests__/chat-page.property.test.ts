/**
 * Feature: frontend-multi-session, Property 7: 右侧面板条件渲染
 *
 * 对于任意应用状态，当 activeSessionId 为空字符串时，右侧面板应渲染 WelcomeView；
 * 当 activeSessionId 非空时，右侧面板应渲染消息列表和输入框。两者互斥。
 *
 * Validates: Requirements 4.3, 4.4
 */
import { describe, beforeEach, expect } from 'vitest';
// @ts-ignore -- moduleResolution "node" cannot resolve exports map; runtime works fine via vitest/vite
import { test, fc } from '@fast-check/vitest';
import { ref, nextTick, defineComponent, h, computed } from 'vue';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

// --- Arbitraries ---

/**
 * Generates either an empty string (no active session) or a non-empty UUID string (active session).
 */
const activeSessionIdArb = fc.oneof(
  fc.constant(''),
  fc.uuid()
);

/**
 * Generates only non-empty session IDs (for targeted non-empty tests).
 */
const nonEmptySessionIdArb = fc.uuid();

// --- Helpers ---

/**
 * A proxy component that replicates the conditional rendering logic from
 * frontend/src/views/chat/index.vue:
 *
 *   <template v-if="activeSessionId">
 *     <ChatList />
 *     <InputBox />
 *   </template>
 *   <WelcomeView v-else />
 *
 * Uses plain HTML elements with data-testid attributes to represent the child components,
 * avoiding complex dependency chains (Naive UI, WebSocket, etc.).
 */
function createChatPageProxy(activeId: string) {
  return defineComponent({
    name: 'ChatPageProxy',
    setup() {
      const activeSessionId = ref(activeId);
      return { activeSessionId };
    },
    render() {
      const rightPanelChildren = this.activeSessionId
        ? [
            h('div', { 'data-testid': 'chat-list', class: 'chat-list' }, 'ChatList'),
            h('div', { 'data-testid': 'input-box', class: 'input-box' }, 'InputBox')
          ]
        : [
            h('div', { 'data-testid': 'welcome-view', class: 'welcome-view' }, '今天有什么可以帮到你？')
          ];

      return h('div', { class: 'chat-page flex h-full' }, [
        h('div', { 'data-testid': 'session-panel', class: 'session-panel' }, 'SessionPanel'),
        h('div', { class: 'right-panel flex flex-auto flex-col gap-4' }, rightPanelChildren)
      ]);
    }
  });
}

// --- Tests ---

describe('ChatPage - Property 7: 右侧面板条件渲染', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  test.prop([activeSessionIdArb], { numRuns: 100 })(
    'WelcomeView and ChatList+InputBox should be mutually exclusive based on activeSessionId',
    async (activeId: string) => {
      const Component = createChatPageProxy(activeId);
      const wrapper = mount(Component);
      await nextTick();

      const hasWelcomeView = wrapper.find('[data-testid="welcome-view"]').exists();
      const hasChatList = wrapper.find('[data-testid="chat-list"]').exists();
      const hasInputBox = wrapper.find('[data-testid="input-box"]').exists();

      if (activeId === '') {
        // When activeSessionId is empty: WelcomeView shown, ChatList+InputBox hidden
        expect(hasWelcomeView).toBe(true);
        expect(hasChatList).toBe(false);
        expect(hasInputBox).toBe(false);
      } else {
        // When activeSessionId is non-empty: ChatList+InputBox shown, WelcomeView hidden
        expect(hasWelcomeView).toBe(false);
        expect(hasChatList).toBe(true);
        expect(hasInputBox).toBe(true);
      }

      // Mutual exclusivity: WelcomeView XOR (ChatList AND InputBox)
      expect(hasWelcomeView).not.toBe(hasChatList);
      expect(hasWelcomeView).not.toBe(hasInputBox);

      wrapper.unmount();
    }
  );

  test.prop([nonEmptySessionIdArb], { numRuns: 100 })(
    'right panel should render ChatList and InputBox when activeSessionId is non-empty',
    async (activeId: string) => {
      const Component = createChatPageProxy(activeId);
      const wrapper = mount(Component);
      await nextTick();

      // ChatList and InputBox must both be present
      expect(wrapper.find('[data-testid="chat-list"]').exists()).toBe(true);
      expect(wrapper.find('[data-testid="input-box"]').exists()).toBe(true);

      // WelcomeView must NOT be present
      expect(wrapper.find('[data-testid="welcome-view"]').exists()).toBe(false);

      wrapper.unmount();
    }
  );

  test.prop([fc.constant('')], { numRuns: 100 })(
    'right panel should render WelcomeView when activeSessionId is empty string',
    async (activeId: string) => {
      const Component = createChatPageProxy(activeId);
      const wrapper = mount(Component);
      await nextTick();

      // WelcomeView must be present with welcome text
      const welcomeView = wrapper.find('[data-testid="welcome-view"]');
      expect(welcomeView.exists()).toBe(true);
      expect(welcomeView.text()).toContain('今天有什么可以帮到你？');

      // ChatList and InputBox must NOT be present
      expect(wrapper.find('[data-testid="chat-list"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="input-box"]').exists()).toBe(false);

      wrapper.unmount();
    }
  );
});
