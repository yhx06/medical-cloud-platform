<script setup lang="ts">
withDefaults(defineProps<{ loading?: boolean; error?: string | null; empty?: boolean; emptyText?: string }>(), {
  loading: false,
  error: null,
  empty: false,
  emptyText: '暂无数据'
})
</script>

<template>
  <div class="async-state">
    <p v-if="loading" role="status" class="message">加载中…</p>
    <p v-else-if="error" role="alert" class="message error">{{ error }}</p>
    <div v-else-if="empty" data-test="empty-state" class="message"><slot name="empty">{{ emptyText }}</slot></div>
    <slot v-else />
  </div>
</template>

<style scoped>
.message{margin:20px 0;padding:24px;text-align:center;border-radius:12px;background:#f4f7f8;color:#587078}
.error{background:#fff0f0;color:#a33434}
</style>
