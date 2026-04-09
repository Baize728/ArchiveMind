<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import SessionPanel from './modules/session-panel.vue';
import WelcomeView from './modules/welcome-view.vue';

const sessionStore = useSessionStore();
const { activeSessionId } = storeToRefs(sessionStore);

const panelCollapsed = ref(false);
</script>

<template>
  <div class="flex h-full">
    <!-- 左侧：会话列表面板 -->
    <SessionPanel :collapsed="panelCollapsed" @toggle="panelCollapsed = !panelCollapsed" />

    <!-- 右侧：聊天区域或欢迎界面 -->
    <div class="flex flex-auto flex-col gap-4">
      <template v-if="activeSessionId">
        <ChatList />
        <InputBox />
      </template>
      <WelcomeView v-else />
    </div>
  </div>
</template>

<style scoped></style>
