<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPatientRecords, getPatientRecord } from '../../api/consultations'
import type { MedicalRecord } from '../../types/business'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import RecordDetails from '../../components/RecordDetails.vue'
import RecordFiles from '../../components/RecordFiles.vue'
const records = ref<MedicalRecord[]>([])
const selected = ref<MedicalRecord>()
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { records.value = await listPatientRecords() })
async function open(record: MedicalRecord) {
  selected.value = undefined
  await perform(async () => { selected.value = await getPatientRecord(record.id) }, '')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">PATIENT / RECORDS</p><h2>我的病历</h2><p class="muted">仅显示本人已完成病历与授权附件。</p></header>
    <button :disabled="loading || busy" @click="refresh">刷新病历</button>
    <WorkflowFeedback :error="error" :message="message" />
    <AsyncState :loading="loading" :empty="!records.length" empty-text="暂无已完成病历">
      <ul class="record-list"><li v-for="record in records" :key="record.id"><span>病历 {{ record.id }}<small>{{ record.completedAt }}</small></span><button data-test="view-record" :disabled="busy" @click="open(record)">查看病历</button></li></ul>
    </AsyncState>
    <p v-if="busy" role="status">正在加载病历详情…</p>
    <template v-if="selected"><RecordDetails :record="selected" /><RecordFiles :key="selected.id" :record-id="selected.id" /></template>
  </section>
</template>
