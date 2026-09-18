<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const labels: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
  PUBLISHED: '已发布',
  BOOKED: '已预约',
  IN_PROGRESS: '接诊中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  PENDING: '待支付',
  PAID: '已支付'
}
const label = computed(() => labels[props.status] ?? props.status)
</script>

<template><span class="status-tag" :data-status="status">{{ label }}</span></template>

<style scoped>
.status-tag{display:inline-flex;padding:3px 9px;border-radius:999px;background:#e7f1f3;color:#123c4a;font-size:13px;font-weight:600}
.status-tag[data-status="DISABLED"],.status-tag[data-status="CANCELLED"]{background:#f3e7e7;color:#883d3d}
.status-tag[data-status="ACTIVE"],.status-tag[data-status="PUBLISHED"],.status-tag[data-status="PAID"],.status-tag[data-status="COMPLETED"]{background:#e5f3ec;color:#276749}
.status-tag[data-status="PENDING"],.status-tag[data-status="BOOKED"]{background:#fff2d8;color:#8a5a00}
</style>
