<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
import { submitFeedback } from '@/service/api/feedback';
import { saveEvalSample } from '@/service/api/eval';
import ThinkingSection from './thinking-section.vue';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

// T1-4: 赞踩反馈
const feedbackLoading = ref(false);
const submittedAction = ref<Api.Feedback.Action | null>(null);

async function handleFeedback(action: Api.Feedback.Action) {
  if (!props.msg.traceId || feedbackLoading.value) return;

  // 如果已提交过相同反馈，不做重复提交
  if (submittedAction.value === action) return;

  feedbackLoading.value = true;
  try {
    const { error } = await submitFeedback({
      conversationId: props.msg.traceId,
      traceId: props.msg.traceId,
      action
    });

    if (error) {
      window.$message?.error('反馈提交失败');
      return;
    }

    submittedAction.value = action;
    const labels: Record<Api.Feedback.Action, string> = {
      LIKE: '已点赞',
      DISLIKE: '已点踩，将用于改进',
      PARTIAL_CORRECT: '已标记部分正确',
      OUTDATED: '已标记内容过时'
    };
    window.$message?.success(labels[action]);
  } catch {
    window.$message?.error('反馈提交失败');
  } finally {
    feedbackLoading.value = false;
  }
}

// 反馈按钮是否可用：仅 assistant 消息且已完成/出错，且有 traceId
const feedbackEnabled = computed(() => {
  return props.msg.role === 'assistant'
    && props.msg.traceId
    && ['finished', 'error'].includes(props.msg.status || '');
});

const chatStore = useChatStore();

// T1-4 二期: 评测标注弹窗
const labelModalVisible = ref(false);
const labelLoading = ref(false);
const labelForm = reactive({
  expectedAnswer: '',
  expectedIntent: '',
  labelNote: ''
});

function openLabelModal() {
  if (!props.msg.traceId) return;
  labelForm.expectedAnswer = '';
  labelForm.expectedIntent = '';
  labelForm.labelNote = '';
  labelModalVisible.value = true;
}

async function submitLabel() {
  if (!labelForm.expectedAnswer.trim()) {
    window.$message?.warning('期望答案不能为空');
    return;
  }
  labelLoading.value = true;
  try {
    const { error } = await saveEvalSample({
      traceId: props.msg.traceId!,
      expectedAnswer: labelForm.expectedAnswer,
      expectedIntent: labelForm.expectedIntent || undefined,
      labelNote: labelForm.labelNote || undefined
    });
    if (error) {
      window.$message?.error('标注保存失败');
      return;
    }
    window.$message?.success('标注已保存，已加入 gold set');
    labelModalVisible.value = false;
  } finally {
    labelLoading.value = false;
  }
}

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
        <div v-else>
          <ThinkingSection
            v-if="msg.thinkingContent"
            :content="msg.thinkingContent"
            :status="msg.status"
          />
          <div class="rounded-2xl rounded-tl-sm bg-#f5f6f8 px-4 py-3 dark:bg-#1e1e1e" @click="handleContentClick">
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
        </div>
        <div class="mt-1 flex items-center gap-1">
          <NButton quaternary size="tiny" @click="handleCopy(msg.content)">
            <template #icon><icon-mynaui:copy class="text-12px" /></template>
          </NButton>
          <!-- T1-4: 赞踩反馈按钮 -->
          <template v-if="feedbackEnabled">
            <NButton
              quaternary
              size="tiny"
              :type="submittedAction === 'LIKE' ? 'primary' : 'default'"
              :loading="feedbackLoading"
              @click="handleFeedback('LIKE')"
            >
              <template #icon><icon-mdi:thumb-up-outline class="text-12px" /></template>
            </NButton>
            <NButton
              quaternary
              size="tiny"
              :type="submittedAction === 'DISLIKE' ? 'error' : 'default'"
              :loading="feedbackLoading"
              @click="handleFeedback('DISLIKE')"
            >
              <template #icon><icon-mdi:thumb-down-outline class="text-12px" /></template>
            </NButton>
            <NDropdown
              trigger="click"
              :options="[
                { label: '部分正确', key: 'PARTIAL_CORRECT' },
                { label: '内容过时', key: 'OUTDATED' }
              ]"
              @select="handleFeedback"
            >
              <NButton quaternary size="tiny">
                <template #icon><icon-mdi:dots-horizontal class="text-12px" /></template>
              </NButton>
            </NDropdown>
            <!-- T1-4 二期: 标注按钮 -->
            <NButton quaternary size="tiny" @click="openLabelModal">
              <template #icon><icon-mdi:label-outline class="text-12px" /></template>
            </NButton>
          </template>
        </div>
      </div>
    </div>

    <!-- T1-4 二期: 评测标注弹窗 -->
    <NModal
      v-model:show="labelModalVisible"
      preset="card"
      title="标注样本"
      style="width: 560px"
      :mask-closable="false"
    >
      <NForm label-placement="top">
        <NFormItem label="期望答案（必填）">
          <NInput
            v-model:value="labelForm.expectedAnswer"
            type="textarea"
            :rows="4"
            placeholder="标准答案，用于回归评测比对"
          />
        </NFormItem>
        <NFormItem label="期望意图（可选）">
          <NSelect
            v-model:value="labelForm.expectedIntent"
            :options="[
              { label: 'KNOWLEDGE_QA', value: 'KNOWLEDGE_QA' },
              { label: 'DOC_OPERATION', value: 'DOC_OPERATION' },
              { label: 'CHITCHAT', value: 'CHITCHAT' },
              { label: 'AMBIGUOUS', value: 'AMBIGUOUS' }
            ]"
            clearable
            placeholder="可选"
          />
        </NFormItem>
        <NFormItem label="备注（可选）">
          <NInput
            v-model:value="labelForm.labelNote"
            type="textarea"
            :rows="2"
            placeholder="标注说明"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton @click="labelModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="labelLoading" @click="submitLabel">保存</NButton>
        </div>
      </template>
    </NModal>
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
