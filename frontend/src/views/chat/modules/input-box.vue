<script setup lang="ts">
const chatStore = useChatStore();
const { input, list, wsStatus, wsData } = storeToRefs(chatStore);

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message && !isSending) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

watch(wsData, val => {
  let data: any = null;
  try {
    data = JSON.parse(val);
  } catch {
    console.warn('Failed to parse stream data:', val);
    return;
  }

  if (!data) return;

  const assistant = list.value[list.value.length - 1];

  // 优先处理 error（兼容旧格式 {"error": "..."}）
  if (data.error) {
    assistant.status = 'error';
    return;
  }

  if (data.type === 'completion' && data.status === 'finished' && assistant.status !== 'error') {
    assistant.status = 'finished';
  } else if (data.type === 'error') {
    assistant.status = 'error';
  } else if (data.type === 'thinking' && data.chunk) {
    assistant.status = 'loading';
    assistant.thinkingContent = (assistant.thinkingContent || '') + data.chunk;
  } else if (data.type === 'answer' && data.chunk) {
    assistant.status = 'loading';
    assistant.content += data.chunk;
  } else if (data.chunk) {
    // 兼容旧格式：没有 type 字段但有 chunk 的数据视为 answer
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
});

const handleSend = async () => {
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  chatStore.wsSend(input.value.message);
  list.value.push({
    content: '',
    thinkingContent: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      placeholder="给 ArchiveMind 发送消息"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
      </div>
      <NButton :disabled="sendable" strong circle type="primary" @click="handleSend">
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped></style>
