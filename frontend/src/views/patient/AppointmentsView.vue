<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPatientAppointments, cancelAppointment } from '../../api/appointments'
import type { Appointment } from '../../types/business'
import { money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const items = ref<Appointment[]>([])
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { items.value = await listPatientAppointments() })
async function cancel(item: Appointment) {
  if (!window.confirm('确认取消本次预约？取消后号源将释放。')) return
  await perform(async () => { const updated = await cancelAppointment(item.id); items.value = items.value.map(row => row.id === item.id ? updated : row) }, '预约已取消')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">PATIENT / APPOINTMENTS</p><h2>我的预约</h2><p class="muted">仅展示本人预约，接诊开始后无法取消。</p></header>
    <button :disabled="loading || busy" @click="refresh">刷新预约</button>
    <WorkflowFeedback :error="error" :message="message" />
    <AsyncState :loading="loading" :empty="!items.length" empty-text="暂无预约，请先前往号源查询">
      <div class="table-scroll"><table><thead><tr><th>预约编号</th><th>日期 / 时段</th><th>医生 / 科室编号</th><th>诊费</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in items" :key="item.id"><td>{{ item.id }}</td><td>{{ item.date }}<small>{{ item.startTime }}–{{ item.endTime }}</small></td><td>{{ item.doctorId }}<small>{{ item.departmentId }}</small></td><td>{{ money(item.fee) }}</td><td><StatusTag :status="item.status" /></td><td><button v-if="item.status === 'BOOKED'" data-test="cancel-appointment" :disabled="busy" @click="cancel(item)">取消预约</button></td></tr>
      </tbody></table></div>
    </AsyncState>
  </section>
</template>
