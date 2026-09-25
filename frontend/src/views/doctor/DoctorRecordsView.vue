<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listDoctorRecords } from '../../api/consultations'
import type { MedicalRecord } from '../../types/business'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import ConsultationEditorView from './ConsultationEditorView.vue'
const records = ref<MedicalRecord[]>([])
const selectedId = ref('')
const selected = computed(() => records.value.find(record => record.id === selectedId.value))
const completed = computed(() => records.value.filter(record => record.status === 'COMPLETED'))
const { loading, error, load } = useWorkflow()
const refresh = () => load(async () => { records.value = await listDoctorRecords() })
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">DOCTOR / RECORDS</p><h2>历史病历</h2></header>
    <p class="notice">仅显示本人、本院的服务端病历；完成后正文不可覆盖，只能追加更正说明。</p>
    <WorkflowFeedback :error="error" />
    <button :disabled="loading" @click="refresh">刷新已完成预约</button>
    <h3>已完成病历</h3>
    <AsyncState :loading="loading" :empty="!completed.length" empty-text="暂无已完成病历"><ul class="record-list"><li v-for="record in completed" :key="record.id">{{ record.id }} <StatusTag :status="record.status" /><button data-test="open-doctor-record" @click="selectedId = record.id">查看 / 追加更正</button></li></ul></AsyncState>
    <ConsultationEditorView v-if="selected" :key="selected.id" :record="selected" />
  </section>
</template>
