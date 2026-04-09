<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{fileName: string, id: string}>>([]);

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 匹配 (来源#数字: 文件名) 的正则表达式
  const sourcePattern = /\(来源#(\d+):\s*([^)]+)\)/g;

  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    // 为文件名创建可点击的链接
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    const fileId = `source-file-${sourceFiles.value.length}`;

    // 存储文件信息
    sourceFiles.value.push({
      fileName: encodedFileName,
      id: fileId
    });

    return `(来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick(file.fileName);
      }
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);
  console.log('点击了来源文件:', decodedFileName);

  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName,
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
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch (err) {
    window.$message?.destroyAll();
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="mb-6">
    <!-- 用户消息：靠右 -->
    <div v-if="msg.role === 'user'" class="flex flex-col items-end">
      <div class="flex flex-row-reverse items-center gap-3">
        <NAvatar :size="36" class="bg-primary flex-shrink-0">
          <SvgIcon icon="ph:user-circle" class="text-5 color-white" />
        </NAvatar>
        <div class="flex flex-col items-end gap-0.5">
          <NText class="text-13px font-600">{{ authStore.userInfo.username }}</NText>
          <NText class="text-11px color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
        </div>
      </div>
      <div class="mr-12 mt-2 max-w-[80%]">
        <div class="rounded-2xl rounded-tr-sm bg-primary/10 px-4 py-3 text-14px color-#333 dark:bg-primary/20 dark:color-#e5e5e5">
          {{ content }}
        </div>
        <div class="mt-1 flex justify-end">
          <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
            <template #icon><icon-mynaui:copy class="text-12px" /></template>
          </NButton>
        </div>
      </div>
    </div>

    <!-- AI 回复：靠左 -->
    <div v-else class="flex flex-col items-start">
      <div class="flex items-center gap-3">
        <NAvatar :size="36" class="bg-primary flex-shrink-0">
          <SystemLogo class="text-5 text-white" />
        </NAvatar>
        <div class="flex flex-col gap-0.5">
          <NText class="text-13px font-600">ArchiveMind</NText>
          <NText class="text-11px color-gray-400">{{ formatDate(msg.timestamp) }}</NText>
        </div>
      </div>
      <div class="ml-12 mt-2 max-w-[85%]">
        <NText v-if="msg.status === 'pending'">
          <icon-eos-icons:three-dots-loading class="text-8" />
        </NText>
        <NText v-else-if="msg.status === 'error'" class="italic color-red-500">服务器繁忙，请稍后再试</NText>
        <div v-else class="rounded-2xl rounded-tl-sm bg-#f5f6f8 px-4 py-3 dark:bg-#1e1e1e" @click="handleContentClick">
          <!-- 工具调用状态 -->
          <div v-if="msg.toolCalls?.length" class="mb-2 flex flex-col gap-1">
            <div
              v-for="tc in msg.toolCalls"
              :key="tc.function"
              class="flex items-center gap-1.5 rounded-lg bg-#e8f4fd px-3 py-1.5 text-12px color-#1890ff dark:bg-#1a2f3f dark:color-#40a9ff"
            >
              <icon-eos-icons:loading v-if="tc.status === 'executing'" class="animate-spin text-14px" />
              <icon-mdi:check-circle-outline v-else class="text-14px color-#52c41a" />
              <span>{{ tc.status === 'executing' ? `正在调用 ${tc.function}` : `${tc.function} 完成` }}</span>
            </div>
          </div>
          <VueMarkdownIt :content="content" />
        </div>
        <div class="mt-1 flex">
          <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
            <template #icon><icon-mynaui:copy class="text-12px" /></template>
          </NButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}
</style>
