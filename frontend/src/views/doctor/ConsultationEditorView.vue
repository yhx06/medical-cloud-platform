<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { MedicalRecord, PrescriptionItemInput } from '../../types/business'
import { saveConsultationDraft, completeConsultation, appendRecordCorrection } from '../../api/consultations'
import { useConsultationWorkspace } from '../../stores/consultationWorkspace'
import { useWorkflow } from '../../composables/useWorkflow'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import StatusTag from '../../components/StatusTag.vue'
import RecordDetails from '../../components/RecordDetails.vue'
import RecordFiles from '../../components/RecordFiles.vue'
const props = defineProps<{ record: MedicalRecord }>()
const emit = defineEmits<{ updated: [record: MedicalRecord] }>()
const workspace = useConsultationWorkspace()
const current = ref(props.record)
const form = reactive({ complaint: '', diagnosis: '', advice: '', prescriptionItems: [] as PrescriptionItemInput[] })
const correction = ref('')
const { busy, error, message, perform } = useWorkflow()
watch(() => props.record, record => {
  current.value = record
  form.complaint = record.complaint ?? ''
  form.diagnosis = record.diagnosis ?? ''
  form.advice = record.advice ?? ''
  form.prescriptionItems = record.prescriptionItems.map(({ medicationName, dosage, frequency, durationDays }) => ({ medicationName, dosage, frequency, durationDays }))
}, { immediate: true })
function receive(record: MedicalRecord) { current.value = record; workspace.remember(record); emit('updated', record) }
function valid(complete: boolean) {
  if (complete && (!form.complaint.trim() || !form.diagnosis.trim() || !form.advice.trim())) {
    error.value = '请填写主诉、诊断和处理建议后再完成接诊'; return false
  }
  if (form.prescriptionItems.some(item => !item.medicationName.trim() || !item.dosage.trim() || !item.frequency.trim() || !Number.isInteger(item.durationDays) || item.durationDays <= 0)) {
    error.value = '请完整填写每条处方，用药天数须为正整数'; return false
  }
  return true
}
async function save(complete = false) {
  if (current.value.status !== 'IN_PROGRESS' || busy.value || !valid(complete)) return
  if (complete && !window.confirm('确认完成接诊？完成后原病历与处方不可覆盖，只能追加更正说明。')) return
  await perform(async () => {
    // Completion accepts no body: the server validates and freezes the previously saved draft.
    const saved = await saveConsultationDraft(current.value.appointmentId, {
      complaint: form.complaint.trim(), diagnosis: form.diagnosis.trim(), advice: form.advice.trim(),
      prescriptionItems: form.prescriptionItems.map(item => ({ ...item, medicationName: item.medicationName.trim(), dosage: item.dosage.trim(), frequency: item.frequency.trim() }))
    })
    receive(saved)
    if (complete) receive(await completeConsultation(current.value.appointmentId))
  }, complete ? '接诊已完成，订单由系统自动生成' : '草稿已保存')
}
async function appendCorrection() {
  if (!correction.value.trim()) { error.value = '请填写更正说明'; return }
  if (!window.confirm('确认追加此更正说明？原始病历将保留。')) return
  await perform(async () => {
    receive(await appendRecordCorrection(current.value.id, correction.value.trim()))
    correction.value = ''
  }, '更正说明已追加')
}
</script>
<template>
  <section class="subsection consultation-editor">
    <h3>接诊编辑器 <StatusTag :status="current.status" /></h3>
    <p class="muted">预约 {{ current.appointmentId }} · 病历 {{ current.id }}</p>
    <WorkflowFeedback :error="error" :message="message" />
    <form v-if="current.status === 'IN_PROGRESS'" @submit.prevent="save(false)">
      <fieldset :disabled="busy">
        <div class="form-grid">
          <label>患者主诉<textarea v-model="form.complaint" name="complaint" rows="3" /></label>
          <label>诊断<textarea v-model="form.diagnosis" name="diagnosis" rows="3" /></label>
          <label>处理建议<textarea v-model="form.advice" name="advice" rows="3" /></label>
        </div>
        <h4>处方项目</h4>
        <p v-if="!form.prescriptionItems.length" class="muted">暂无处方，可按需要添加。</p>
        <div v-for="(item, index) in form.prescriptionItems" :key="index" class="prescription-row">
          <label>药品名称 {{ index + 1 }}<input v-model="item.medicationName" :name="'medication-' + index" required /></label>
          <label>用法 / 剂量<input v-model="item.dosage" :name="'dosage-' + index" required /></label>
          <label>用药频次<input v-model="item.frequency" :name="'frequency-' + index" required /></label>
          <label>用药天数<input v-model.number="item.durationDays" :name="'duration-' + index" type="number" min="1" step="1" required /></label>
          <button type="button" :aria-label="'移除第' + (index + 1) + '条处方'" @click="form.prescriptionItems.splice(index, 1)">移除</button>
        </div>
        <div class="actions">
          <button type="button" data-test="add-prescription" @click="form.prescriptionItems.push({ medicationName: '', dosage: '', frequency: '', durationDays: 1 })">添加处方</button>
          <button data-test="save-draft">保存草稿</button>
          <button type="button" class="primary" data-test="complete-consultation" @click="save(true)">完成接诊</button>
        </div>
      </fieldset>
      <p v-if="current.draftSavedAt" class="muted">上次保存：{{ current.draftSavedAt }}</p>
    </form>
    <template v-else>
      <p class="notice">病历已完成，原始病历和处方已锁定。需要补充时请追加更正说明。</p>
      <RecordDetails :record="current" />
      <form @submit.prevent="appendCorrection"><label>更正说明<textarea v-model="correction" name="correction" rows="3" required :disabled="busy" /></label><button data-test="append-correction" :disabled="busy">追加更正</button></form>
    </template>
    <RecordFiles :key="current.id" :record-id="current.id" allow-upload />
  </section>
</template>
