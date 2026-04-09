<script setup lang="ts">
import { NButton, NEmpty, NIcon, NSpin, useDialog } from 'naive-ui';

defineOptions({
  name: 'SessionPanel'
});

interface Props {
  collapsed?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  collapsed: false
});

const emit = defineEmits<{
  toggle: [];
}>();

const sessionStore = useSessionStore();
const { sessionList, activeSessionId, loading, creating } = storeToRefs(sessionStore);
const dialog = useDialog();

const loadError = ref(false);

/** 获取有序的分组列表 */
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

async function handleLoad() {
  loadError.value = false;
  try { await sessionStore.loadSessionList(); } catch { loadError.value = true; }
}
async function handleCreate() { await sessionStore.createSession(); }
async function handleSwitch(sessionId: string) {
  if (sessionId === activeSessionId.value) return;
  await sessionStore.switchSession(sessionId);
}
function handleDelete(e: Event, sessionId: string) {
  e.stopPropagation();
  dialog.warning({
    title: '确认删除',
    content: '删除后无法恢复，确定要删除该会话吗？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => sessionStore.deleteSession(sessionId)
  });
}

onMounted(() => { handleLoad(); });
</script>

<template>
  <div
    class="flex h-full flex-col b-r-1 b-#e5e5e5/60 bg-#f7f8fa text-#333 transition-all duration-300 dark:b-#333 dark:bg-#141414 dark:text-#e5e5e5"
    :class="collapsed ? 'w-48px' : 'w-260px'"
  >
    <!-- 展开状态 -->
    <template v-if="!collapsed">
      <div class="p-3">
        <NButton type="primary" block round :loading="creating" :disabled="creating" @click="handleCreate">
          <template #icon><NIcon><icon-material-symbols:add /></NIcon></template>
          开启新对话
        </NButton>
      </div>

      <div class="flex-auto overflow-y-auto px-2 pb-2">
        <NSpin v-if="loading" class="mt-8 w-full" />
        <div v-else-if="loadError" class="mt-8 flex flex-col items-center gap-2">
          <span class="text-13px color-#999 dark:color-gray-400">加载失败</span>
          <NButton size="small" round @click="handleLoad">重试</NButton>
        </div>
        <NEmpty v-else-if="isEmpty" class="mt-8" description="暂无会话，点击上方按钮开启新对话" />
        <template v-else>
          <div v-for="group in orderedGroups" :key="group.key" class="mb-3">
            <div class="px-2 py-1.5 text-11px font-500 uppercase tracking-wider color-#aaa dark:color-gray-500">{{ group.label }}</div>
            <div
              v-for="session in group.sessions"
              :key="session.sessionId"
              class="group flex cursor-pointer items-center rounded-lg px-3 py-2.5 text-13px color-#444 transition-all hover:bg-#eef1f5 dark:color-#ccc dark:hover:bg-#1e1e1e"
              :class="{ 'bg-primary/10 color-primary font-medium dark:bg-primary/15 dark:color-primary': activeSessionId === session.sessionId }"
              @click="handleSwitch(session.sessionId)"
            >
              <NIcon class="mr-2 flex-shrink-0 text-15px opacity-50" :class="{ 'opacity-100': activeSessionId === session.sessionId }">
                <icon-material-symbols:chat-bubble-outline />
              </NIcon>
              <span class="flex-auto truncate">{{ session.title }}</span>
              <NIcon class="ml-1 flex-shrink-0 opacity-0 transition-opacity group-hover:opacity-70 hover:!opacity-100" :size="15" @click="handleDelete($event, session.sessionId)">
                <icon-material-symbols:delete-outline />
              </NIcon>
            </div>
          </div>
        </template>
      </div>
    </template>

    <!-- 收起状态：只显示新建按钮图标 -->
    <template v-else>
      <div class="flex flex-col items-center gap-2 pt-3">
        <NButton type="primary" circle :loading="creating" :disabled="creating" @click="handleCreate">
          <template #icon><NIcon><icon-material-symbols:add /></NIcon></template>
        </NButton>
      </div>
    </template>

    <!-- 底部：折叠/展开按钮 -->
    <div class="flex-shrink-0 b-t-1 b-#e5e5e5/60 p-2 dark:b-#333">
      <div
        class="flex cursor-pointer items-center justify-center rounded-lg py-2 transition-colors hover:bg-#eef1f5 dark:hover:bg-#1e1e1e"
        @click="emit('toggle')"
      >
        <NIcon :size="18" class="color-#999 transition-transform duration-300" :class="collapsed ? 'rotate-180' : ''">
          <icon-material-symbols:chevron-left />
        </NIcon>
      </div>
    </div>
  </div>
</template>

<style scoped></style>
