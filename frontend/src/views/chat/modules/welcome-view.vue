<script setup lang="ts">
import { NButton, NIcon } from 'naive-ui';

defineOptions({
  name: 'WelcomeView'
});

const sessionStore = useSessionStore();
const chatStore = useChatStore();
const { creating } = storeToRefs(sessionStore);

const quickPrompts = [
  { icon: '💡', text: '帮我总结一下最近上传的文档要点' },
  { icon: '🔍', text: '在知识库中搜索相关资料' },
  { icon: '📝', text: '帮我写一份工作报告的大纲' },
  { icon: '🤔', text: '解释一下这个概念的含义' }
];

async function handleCreate() {
  await sessionStore.createSession();
}

async function handleQuickPrompt(prompt: string) {
  await sessionStore.createSession();
  chatStore.input.message = prompt;
}
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center px-6">
    <!-- Logo + 欢迎语 -->
    <div class="mb-8 flex flex-col items-center gap-3">
      <SystemLogo class="w-64px h-64px" />
      <h2 class="text-26px font-medium color-gray-700 dark:color-gray-200">
        今天有什么可以帮到你？
      </h2>
      <p class="text-14px color-gray-400">选择下方话题快速开始，或开启新对话</p>
    </div>

    <!-- 快捷提问卡片 -->
    <div class="mb-8 grid w-full max-w-600px grid-cols-2 gap-3">
      <div
        v-for="(item, index) in quickPrompts"
        :key="index"
        class="cursor-pointer rounded-xl b-1 b-gray-200 bg-white px-4 py-3 transition-all hover:b-primary hover:shadow-md dark:b-gray-700 dark:bg-gray-800 dark:hover:b-primary"
        @click="handleQuickPrompt(item.text)"
      >
        <div class="mb-1 text-18px">{{ item.icon }}</div>
        <div class="text-13px color-gray-600 line-clamp-2 dark:color-gray-300">{{ item.text }}</div>
      </div>
    </div>

    <!-- 开启新对话按钮 -->
    <NButton type="primary" size="large" round :loading="creating" :disabled="creating" @click="handleCreate">
      <template #icon>
        <NIcon>
          <icon-material-symbols:add />
        </NIcon>
      </template>
      开启新对话
    </NButton>
  </div>
</template>

<style scoped></style>
