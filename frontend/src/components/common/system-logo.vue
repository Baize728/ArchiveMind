<script lang="ts" setup>
import logoUrl from '@/assets/svg-icon/icon.svg?url';

defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <img :src="logoUrl" alt="ArchiveMind" class="size-full object-contain" />
</template>

<style scoped></style>
