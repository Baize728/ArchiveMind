<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="w-full flex flex-col items-center justify-center gap-2 overflow-visible py-2">
    <SystemLogo class="w-48px h-48px flex-shrink-0" />
    <h2 v-show="showTitle" class="text-15px text-primary font-700 tracking-wide" style="font-family: 'Dancing Script', cursive; white-space: nowrap;">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>

<style scoped></style>
