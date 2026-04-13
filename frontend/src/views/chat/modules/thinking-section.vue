<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';

defineOptions({ name: 'ThinkingSection' });

const props = defineProps<{
  content: string;
  status?: 'pending' | 'loading' | 'finished' | 'error';
}>();

const collapsed = ref(true);
const userToggled = ref(false);

watch(
  () => props.status,
  (newStatus, oldStatus) => {
    if (userToggled.value) return;
    if (newStatus === 'loading') {
      collapsed.value = false;
    } else if (oldStatus === 'loading' && newStatus === 'finished') {
      collapsed.value = true;
    }
  }
);

const statusText = computed(() => {
  return props.status === 'loading' ? '思考中...' : '已完成思考';
});

function toggle() {
  userToggled.value = true;
  collapsed.value = !collapsed.value;
}
</script>

<template>
  <section
    class="thinking-section mb-3 overflow-hidden rounded-lg"
    aria-label="AI思考过程"
    role="region"
  >
    <button
      class="flex w-full cursor-pointer items-center gap-2 border-none bg-#f7f7fa px-3 py-2 text-12px color-gray-400 dark:bg-#252528"
      :aria-expanded="!collapsed"
      @click="toggle"
      @keydown.enter="toggle"
      @keydown.space.prevent="toggle"
    >
      <icon-eos-icons:loading v-if="status === 'loading'" class="text-13px color-gray-400" />
      <icon-material-symbols:check-circle-outline v-else class="text-13px color-#52c41a" />
      <span>{{ statusText }}</span>
      <span
        class="ml-auto text-14px transition-transform duration-200"
        :class="collapsed ? '' : 'rotate-180'"
      >▾</span>
    </button>
    <div
      v-if="!collapsed"
      class="thinking-body border-l-2 border-l-gray-200 bg-#fafafa px-4 py-3 dark:border-l-gray-600 dark:bg-#1a1a1d"
      aria-live="polite"
    >
      <VueMarkdownIt :content="content" class="text-13px leading-relaxed color-gray-500 dark:color-gray-400" />
    </div>
  </section>
</template>

<style scoped>
.thinking-section {
  border: 1px solid rgba(0, 0, 0, 0.06);
}
:deep(.thinking-body p) {
  margin: 0.3em 0;
}
</style>
