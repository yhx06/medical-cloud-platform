<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getHospitalStatistics } from '../../api/statistics'
import type { HospitalStatistics } from '../../types/business'
import { currentDateRange, money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const range = reactive(currentDateRange())
const stats = ref<HospitalStatistics>()
const { loading, error, load } = useWorkflow()
async function refresh() {
  if (!range.from || !range.to || range.from > range.to) { error.value = '请选择有效日期范围，开始日期不能晚于结束日期'; return }
  await load(async () => { stats.value = await getHospitalStatistics(range.from, range.to) })
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">HOSPITAL / STATISTICS</p><h2>机构统计</h2><p class="muted">仅统计本院业务。预约按就诊日期、收入按支付日期汇总。</p></header>
    <form class="form-grid" @submit.prevent="refresh"><label>开始日期<input v-model="range.from" name="from" type="date" required /></label><label>结束日期<input v-model="range.to" name="to" type="date" required /></label><button :disabled="loading">查询统计</button></form>
    <WorkflowFeedback :error="error" />
    <AsyncState :loading="loading" :empty="!stats" empty-text="暂无统计结果"><template v-if="stats">
      <p class="muted">统计期间：{{ stats.from }} 至 {{ stats.to }}</p>
      <div class="metric-grid"><article><span>预约总量</span><strong>{{ stats.totalAppointmentCount }}</strong></article><article><span>取消预约</span><strong>{{ stats.cancelledAppointmentCount }}</strong></article><article><span>已完成预约</span><strong>{{ stats.completedAppointmentCount }}</strong></article><article><span>医生完成接诊数</span><strong>{{ stats.doctorCompletedConsultationCount }}</strong></article><article><span>已支付收入</span><strong>{{ money(stats.paidRevenue) }}</strong></article></div>
    </template></AsyncState>
  </section>
</template>
