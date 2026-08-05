<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
import { type ChatSourceFile, prepareChatMarkdown } from '@/utils/chat-markdown';
import ThinkingSection from './thinking-section.vue';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

const sourceFiles = ref<ChatSourceFile[]>([]);
const rawContent = computed(() => props.msg.content ?? '');
const preparedContent = computed(() => {
  if (props.msg.role === 'assistant') {
    return prepareChatMarkdown(rawContent.value);
  }

  return {
    content: rawContent.value,
    sourceFiles: []
  };
});

const content = computed(() => preparedContent.value.content);

watch(
  preparedContent,
  prepared => {
    sourceFiles.value = prepared.sourceFiles;
  },
  { immediate: true }
);

watch(
  rawContent,
  () => {
    chatStore.scrollToBottom?.();
  },
  { immediate: true }
);

function handleContentClick(event: MouseEvent) {
  if (!(event.target instanceof Element)) {
    return;
  }

  const sourceLink = event.target.closest<HTMLAnchorElement>('a[href^="#source-file-"]');
  if (!sourceLink) {
    return;
  }

  event.preventDefault();

  const fileId = sourceLink.getAttribute('href')?.slice(1);
  const file = sourceFiles.value.find(item => item.id === fileId);
  if (file) {
    handleSourceFileClick(file.fileName);
  }
}

async function handleSourceFileClick(fileName: string) {
  try {
    window.$message?.loading(`正在获取文件下载链接: ${fileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName,
        token: authStore.token
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }

    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${fileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch {
    window.$message?.destroyAll();
    window.$message?.error(`文件下载失败: ${fileName}`);
  }
}
</script>

<template>
  <div class="mb-6">
    <!-- 用户消息：靠右 -->
    <div v-if="msg.role === 'user'" class="flex flex-col items-end">
      <div class="flex flex-row-reverse items-center gap-3">
        <NAvatar :size="36" class="flex-shrink-0 bg-primary">
          <SvgIcon icon="ph:user-circle" class="text-5 color-white" />
        </NAvatar>
        <div class="flex flex-col items-end gap-0.5">
          <NText class="text-13px font-600">{{ authStore.userInfo.username }}</NText>
          <NText class="text-11px color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
        </div>
      </div>
      <div class="mr-12 mt-2 max-w-[80%]">
        <div
          class="rounded-2xl rounded-tr-sm bg-primary/10 px-4 py-3 text-14px color-#333 dark:bg-primary/20 dark:color-#e5e5e5"
        >
          {{ content }}
        </div>
        <div class="mt-1 flex justify-end">
          <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
            <template #icon><SvgIcon icon="mynaui:copy" class="text-12px" /></template>
          </NButton>
        </div>
      </div>
    </div>

    <!-- AI 回复：靠左 -->
    <div v-else class="flex flex-col items-start">
      <div class="flex items-center gap-3">
        <NAvatar :size="36" class="flex-shrink-0 bg-primary">
          <SystemLogo class="text-5 text-white" />
        </NAvatar>
        <div class="flex flex-col gap-0.5">
          <NText class="text-13px font-600">ArchiveMind</NText>
          <NText class="text-11px color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
        </div>
      </div>
      <div class="ml-12 mt-2 max-w-[85%] min-w-0">
        <NText v-if="msg.status === 'pending'">
          <SvgIcon icon="eos-icons:three-dots-loading" class="text-8" />
        </NText>
        <NText v-else-if="msg.status === 'error'" class="color-red-500 italic">服务器繁忙，请稍后再试</NText>
        <div v-else>
          <ThinkingSection v-if="msg.thinkingContent" :content="msg.thinkingContent" :status="msg.status" />
          <div class="rounded-2xl rounded-tl-sm bg-#f5f6f8 px-4 py-3 dark:bg-#1e1e1e" @click="handleContentClick">
            <!-- 工具调用状态 -->
            <div v-if="msg.toolCalls?.length" class="mb-2 flex flex-col gap-1">
              <div
                v-for="tc in msg.toolCalls"
                :key="tc.function"
                class="flex items-center gap-1.5 rounded-lg bg-#e8f4fd px-3 py-1.5 text-12px color-#1890ff dark:bg-#1a2f3f dark:color-#40a9ff"
              >
                <SvgIcon
                  v-if="tc.status === 'executing'"
                  icon="eos-icons:loading"
                  class="animate-spin text-14px"
                />
                <SvgIcon v-else icon="mdi:check-circle-outline" class="text-14px color-#52c41a" />
                <span>{{ tc.status === 'executing' ? `正在调用 ${tc.function}` : `${tc.function} 完成` }}</span>
              </div>
            </div>
            <VueMarkdownIt :content="content" class="chat-markdown" />
          </div>
        </div>
        <div class="mt-1 flex">
          <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
            <template #icon><SvgIcon icon="mynaui:copy" class="text-12px" /></template>
          </NButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.chat-markdown) {
  min-width: 0;
  overflow-wrap: anywhere;
  color: inherit;
  font-size: 14px;
  line-height: 1.75;

  h1,
  h2,
  h3,
  h4,
  h5,
  h6 {
    margin: 20px 0 10px;
    padding: 0;
    border: 0;
    font-weight: 650;
    letter-spacing: 0;
    line-height: 1.45;
  }

  h1 {
    margin-top: 0;
    font-size: 24px;
  }

  h2 {
    font-size: 20px;
  }

  h3 {
    font-size: 18px;
  }

  h4,
  h5,
  h6 {
    font-size: 16px;
  }

  > :first-child {
    margin-top: 0;
  }

  > :last-child {
    margin-bottom: 0;
  }

  p {
    margin: 0 0 12px;
    line-height: 1.8;
  }

  ul,
  ol {
    margin: 8px 0 14px;
    padding-left: 1.5rem;
  }

  li {
    line-height: 1.75;
  }

  li + li {
    margin-top: 4px;
  }

  li > p {
    margin: 0;
  }

  blockquote {
    margin: 14px 0;
    border-left: 3px solid #b8c2cc;
    padding-left: 12px;
    color: #5b6572;
  }

  :deep(.dark) & blockquote {
    border-left-color: #536170;
    color: #b6c0ca;
  }

  a {
    overflow-wrap: anywhere;
  }

  a[href^='#source-file-'] {
    display: inline;
    margin: 0 2px;
    border-radius: 4px;
    padding: 2px 6px;
    color: #1677ff;
    background: rgb(22 119 255 / 10%);
    font-weight: 500;
    text-decoration: none;
    transition:
      color 0.2s,
      background-color 0.2s;
  }

  a[href^='#source-file-']:hover {
    color: #0958d9;
    background: rgb(22 119 255 / 16%);
  }

  :deep(.dark) & a[href^='#source-file-'] {
    color: #69b1ff;
    background: rgb(105 177 255 / 14%);
  }

  :deep(.dark) & a[href^='#source-file-']:hover {
    color: #91caff;
    background: rgb(105 177 255 / 22%);
  }

  :not(pre) > code {
    border-radius: 4px;
    padding: 2px 5px;
    color: #c41d7f;
    background: rgb(0 0 0 / 6%);
    font-size: 0.9em;
  }

  :deep(.dark) & :not(pre) > code {
    color: #ffadd2;
    background: rgb(255 255 255 / 10%);
  }

  div[class*='language-'] {
    max-width: 100%;
    margin: 14px 0;
    overflow-x: auto;
    border-radius: 8px;
  }

  table {
    max-width: 100%;
    margin: 14px 0;
  }

  img {
    max-width: 100%;
    height: auto;
  }

  hr {
    margin: 18px 0;
  }
}
</style>
