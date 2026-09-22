<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { listPatientAppointments, listDoctorAppointments } from '../api/appointments'
import { listPatientOrders } from '../api/orders'
import { getHospitalStatistics, getPlatformStatistics } from '../api/statistics'
import { menusFor } from '../navigation/roleMenus'
import { currentDateRange, money, useWorkflow } from '../composables/useWorkflow'
import AsyncState from '../components/AsyncState.vue'
import WorkflowFeedback from '../components/WorkflowFeedback.vue'
const auth = useAuthStore()
const links = computed(() => auth.role ? menusFor(auth.role).filter(item => item.key !== 'dashboard') : [])
const metrics = ref<{ key: string; label: string; value: string | number }[]>([])
const period = ref('')
const { loading, error, load } = useWorkflow()
const refresh = () => load(async () => {
  metrics.value = []
  const range = currentDateRange()
  if (auth.role === 'PATIENT') {
    const [appointments, orders] = await Promise.all([listPatientAppointments(), listPatientOrders()])
    metrics.value = [{ key: 'appointments', label: '我的预约总数', value: appointments.length }, { key: 'pending', label: '待支付订单', value: orders.filter(item => item.status === 'PENDING').length }]
  } else if (auth.role === 'DOCTOR') {
    const appointments = await listDoctorAppointments()
    metrics.value = [{ key: 'appointments', label: '本人预约总数', value: appointments.length }, { key: 'pending', label: '待接诊', value: appointments.filter(item => item.status === 'BOOKED').length }, { key: 'active', label: '接诊中', value: appointments.filter(item => item.status === 'IN_PROGRESS').length }]
  } else if (auth.role === 'HOSPITAL_ADMIN') {
    const stats = await getHospitalStatistics(range.from, range.to)
    period.value = stats.from + ' 至 ' + stats.to
    metrics.value = [{ key: 'appointments', label: '本院预约', value: stats.totalAppointmentCount }, { key: 'completed', label: '已完成预约', value: stats.completedAppointmentCount }, { key: 'revenue', label: '已支付收入', value: money(stats.paidRevenue) }]
  } else if (auth.role === 'PLATFORM_ADMIN') {
    const stats = await getPlatformStatistics(range.from, range.to)
    period.value = stats.from + ' 至 ' + stats.to
    metrics.value = [{ key: 'tenants', label: '医院数', value: stats.tenants.length }, { key: 'appointments', label: '预约总量', value: stats.tenants.reduce((sum, item) => sum + item.appointmentCount, 0) }, { key: 'revenue', label: '已支付收入', value: money(stats.tenants.reduce((sum, item) => sum + item.paidRevenue, 0)) }]
  }
})
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">ROLE WORKSPACE</p><h2>欢迎，{{ auth.user?.username }}</h2><p class="muted">从这里进入您的医疗云工作区。</p></header>
    <button :disabled="loading" @click="refresh">刷新工作台</button>
    <WorkflowFeedback :error="error" />
    <AsyncState :loading="loading"><p v-if="period" class="muted">统计期间：{{ period }}</p><div class="metric-grid"><article v-for="metric in metrics" :key="metric.key" :data-test="'metric-' + metric.key"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong></article></div></AsyncState>
    <h3>快捷入口</h3><nav class="quick-links" aria-label="工作台快捷入口"><RouterLink v-for="item in links" :key="item.key" :to="item.path">{{ item.label }} →</RouterLink></nav>
  </section>
</template>
