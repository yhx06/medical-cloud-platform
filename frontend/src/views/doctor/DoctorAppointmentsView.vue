<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listDoctorAppointments } from '../../api/appointments'
import { listDoctorRecords, startConsultation } from '../../api/consultations'
import type { Appointment, MedicalRecord } from '../../types/business'
import { useConsultationWorkspace } from '../../stores/consultationWorkspace'
import { currentDateRange, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import ConsultationEditorView from './ConsultationEditorView.vue'
const workspace = useConsultationWorkspace()
const appointments = ref<Appointment[]>([])
const activeId = ref('')
const selected = computed(() => workspace.records.find(record => record.appointmentId === activeId.value))
const date = ref('')
const status = ref('')
const visible = computed(() => appointments.value.filter(item => (!date.value || item.date === date.value) && (!status.value || item.status === status.value)))
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => {
  const [assigned, records] = await Promise.all([listDoctorAppointments(), listDoctorRecords()])
  appointments.value = assigned
  records.forEach(workspace.remember)
})
function updated(record: MedicalRecord) {
  const item = appointments.value.find(item => item.id === record.appointmentId)
  if (item) item.status = record.status
}
async function start(item: Appointment) {
  if (!window.confirm('确认开始本次接诊？开始后患者不能取消预约。')) return
  await perform(async () => {
    const record = await startConsultation(item.id)
    workspace.remember(record)
    activeId.value = item.id
    updated(record)
  }, '已开始接诊')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">DOCTOR / APPOINTMENTS</p><h2>我的接诊预约</h2><p class="muted">仅显示分配给本人、本院的预约，服务端草稿可在刷新后恢复。</p></header>
    <div class="form-grid"><label>日期<input v-model="date" type="date" /></label><label>状态<select v-model="status"><option value="">全部</option><option value="BOOKED">待接诊</option><option value="IN_PROGRESS">接诊中</option><option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option></select></label><button @click="date = currentDateRange().to">今日预约</button><button @click="date = ''; status = ''">全部预约</button><button :disabled="loading || busy" @click="refresh">刷新</button></div>
    <WorkflowFeedback :error="error" :message="message" />
    <AsyncState :loading="loading" :empty="!visible.length" empty-text="暂无符合条件的预约">
      <div class="table-scroll"><table><thead><tr><th>预约</th><th>患者编号</th><th>就诊时段</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in visible" :key="item.id"><td>{{ item.id }}</td><td>{{ item.patientId }}</td><td>{{ item.date }}<small>{{ item.startTime }}–{{ item.endTime }}</small></td><td><StatusTag :status="item.status" /></td><td>
          <button v-if="item.status === 'BOOKED'" data-test="start-consultation" :disabled="busy" @click="start(item)">开始接诊</button>
          <button v-else-if="workspace.records.some(record => record.appointmentId === item.id)" :disabled="busy" @click="activeId = item.id">打开本次接诊</button>
          <span v-else-if="item.status === 'IN_PROGRESS'" class="muted">接诊草稿暂未找到</span>
        </td></tr>
      </tbody></table></div>
    </AsyncState>
    <ConsultationEditorView v-if="selected" :key="selected.id" :record="selected" @updated="updated" />
    <p class="notice">病历草稿与历史记录保存在服务器，刷新后可继续处理。</p>
  </section>
</template>
