<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listDoctorAppointments } from '../../api/appointments'
import type { Appointment } from '../../types/business'
import { useConsultationWorkspace } from '../../stores/consultationWorkspace'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import ConsultationEditorView from './ConsultationEditorView.vue'
const workspace = useConsultationWorkspace()
const appointments = ref<Appointment[]>([])
const selectedId = ref('')
const selected = computed(() => workspace.records.find(record => record.id === selectedId.value))
const completed = computed(() => workspace.records.filter(record => record.status === 'COMPLETED'))
const { loading, error, load } = useWorkflow()
const refresh = () => load(async () => { appointments.value = (await listDoctorAppointments()).filter(item => item.status === 'COMPLETED') })
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">DOCTOR / RECORDS</p><h2>历史病历</h2></header>
    <p class="notice">当前服务未提供医生历史病历读取接口。下方是本人已完成预约，以及本次浏览器会话返回的已完成病历；刷新浏览器后不能重新读取病历正文。请从接诊预约开始并在同一会话完成编辑。</p>
    <WorkflowFeedback :error="error" />
    <button :disabled="loading" @click="refresh">刷新已完成预约</button>
    <h3>已完成预约</h3>
    <AsyncState :loading="loading" :empty="!appointments.length" empty-text="暂无已完成预约"><ul class="record-list"><li v-for="item in appointments" :key="item.id">{{ item.id }} · {{ item.date }} <StatusTag :status="item.status" /></li></ul></AsyncState>
    <h3>本次会话已完成病历</h3>
    <p v-if="!completed.length" class="muted">暂无可打开的本次会话病历。</p>
    <ul v-else class="record-list"><li v-for="record in completed" :key="record.id">{{ record.id }}<button @click="selectedId = record.id">查看 / 追加更正</button></li></ul>
    <ConsultationEditorView v-if="selected" :key="selected.id" :record="selected" />
  </section>
</template>
