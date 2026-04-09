import { fetchCreateSession, fetchDeleteSession, fetchSessionList, fetchSwitchSession } from '@/service/api';
import { useChatStore } from '../chat';

export const useSessionStore = defineStore(SetupStoreId.Session, () => {
  const chatStore = useChatStore();

  const sessionList = ref<Api.Chat.GroupedSessionList>({
    today: [],
    week: [],
    month: [],
    earlier: {}
  });

  const activeSessionId = ref<string>('');
  const loading = ref<boolean>(false);
  const creating = ref<boolean>(false);

  /** 加载会话列表 */
  async function loadSessionList() {
    loading.value = true;
    try {
      const { error, data } = await fetchSessionList();
      if (!error) {
        sessionList.value = data;
      } else {
        window.$message?.error('加载会话列表失败');
      }
    } finally {
      loading.value = false;
    }
  }

  /** 创建新会话 */
  async function createSession() {
    creating.value = true;
    try {
      const { error, data } = await fetchCreateSession();
      if (!error) {
        await loadSessionList();
        activeSessionId.value = data.sessionId;
        chatStore.clearMessages();
      } else {
        window.$message?.error('创建会话失败');
      }
    } finally {
      creating.value = false;
    }
  }

  /** 切换会话 */
  async function switchSession(sessionId: string) {
    const { error, data } = await fetchSwitchSession(sessionId);
    if (!error) {
      activeSessionId.value = data.sessionId;
      chatStore.setMessages(data.messages);
    } else {
      window.$message?.error('切换会话失败');
    }
  }

  /** 删除会话 */
  async function deleteSession(sessionId: string) {
    const { error } = await fetchDeleteSession(sessionId);
    if (!error) {
      const wasActive = activeSessionId.value === sessionId;
      await loadSessionList();
      if (wasActive) {
        activeSessionId.value = '';
        chatStore.clearMessages();
      }
    } else {
      window.$message?.error('删除会话失败');
    }
  }

  return {
    sessionList,
    activeSessionId,
    loading,
    creating,
    loadSessionList,
    createSession,
    switchSession,
    deleteSession
  };
});
