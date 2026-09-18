<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ page: number; size: number; totalElements: number }>()
const emit = defineEmits<{ change: [page: number] }>()
const totalPages = computed(() => Math.max(1, Math.ceil(props.totalElements / props.size)))
</script>

<template>
  <nav class="pagination" aria-label="分页">
    <button data-test="previous-page" :disabled="page <= 0" @click="emit('change', page - 1)">上一页</button>
    <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
    <button data-test="next-page" :disabled="page >= totalPages - 1" @click="emit('change', page + 1)">下一页</button>
  </nav>
</template>

<style scoped>
.pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:20px;color:#587078}
button{border:1px solid #c9d8dc;background:white;padding:7px 12px;border-radius:7px;color:#123c4a;cursor:pointer}
button:disabled{opacity:.45;cursor:not-allowed}
</style>
