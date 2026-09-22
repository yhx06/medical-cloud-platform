<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getPlatformStatistics } from '../../api/statistics'
import type { PlatformStatistics } from '../../types/business'
import { currentDateRange, money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const range = reactive(currentDateRange())
const stats = ref<PlatformStatistics>()
const { loading, error, load } = useWorkflow()
async function refresh() {
  if (!range.from || !range.to || range.from > range.to) { error.value = '请选择有效日期范围，开始日期不能晚于结束日期'; return }
  await load(async () => { stats.value = await getPlatformStatistics(range.from, range.to) })
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">PLATFORM / STATISTICS</p><h2>平台统计</h2><p class="muted">按医院展示脱敏汇总指标。</p></header>
    <form class="form-grid" @submit.prevent="refresh"><label>开始日期<input v-model="range.from" name="from" type="date" required /></label><label>结束日期<input v-model="range.to" name="to" type="date" required /></label><button :disabled="loading">查询统计</button></form>
    <WorkflowFeedback :error="error" />
    <AsyncState :loading="loading" :empty="!stats?.tenants.length" empty-text="暂无医院统计数据"><template v-if="stats"><p class="muted">统计期间：{{ stats.from }} 至 {{ stats.to }}</p><div class="table-scroll"><table><thead><tr><th>医院编号</th><th>状态</th><th>账号数</th><th>预约量</th><th>完成量</th><th>已支付收入</th></tr></thead><tbody><tr v-for="tenant in stats.tenants" :key="tenant.tenantId"><td>{{ tenant.tenantId }}</td><td><StatusTag :status="tenant.tenantStatus" /></td><td>{{ tenant.accountCount }}</td><td>{{ tenant.appointmentCount }}</td><td>{{ tenant.completedAppointmentCount }}</td><td>{{ money(tenant.paidRevenue) }}</td></tr></tbody></table></div></template></AsyncState>
  </section>
</template>
