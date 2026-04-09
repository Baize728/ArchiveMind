<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';

defineOptions({ name: 'ThinkingSection' });

const props = defineProps<{
  content: string;
  status?: 'pending' | 'loading' | 'finished' | 'error';
}>();

const collapsed = ref(false);

const statusText = computed(() => {
  return props.status === 'loading' ? '思考中...' : '已完成思考';
});

function toggle() {
  collapsed.value = !collapsed.value;
}
</script>

<template>
  <section
    class="mb-3 rounded-xl bg-#f0f0f5/60 dark:bg-#2a2a2e"
    aria-label="AI思考过程"
    role="region"
  >
    <button
      class="flex w-full cursor-pointer items-center gap-2 border-none bg-transparent px-4 py-2.5 text-13px color-gray-500"
      :aria-expanded="!collapsed"
      @click="toggle"
      @keydown.enter="toggle"
      @keydown.space.prevent="toggle"
    >
      <icon-eos-icons:loading v-if="status === 'loading'" class="text-14px" />
      <icon-material-symbols:check-circle-outline v-else class="text-14px color-green" />
      <span>{{ statusText }}</span>
      <span class="ml-auto text-16px transition-transform duration-300" :class="{ 'rotate-180': !collapsed }">
        ▾
      </span>
    </button>
    <div
      v-if="!collapsed"
      class="overflow-hidden px-4 pb-3"
      aria-live="polite"
    >
      <VueMarkdownIt :content="content" class="text-13px color-gray-600 dark:color-gray-400" />
    </div>
  </section>
</template>
