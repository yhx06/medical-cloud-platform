<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { changeScheduleSlotStatus, listDoctors, listHospitalScheduleSlots, publishScheduleSlot } from '../../api/administration'
import type { Doctor, ScheduleSlot } from '../../types/business'
import { money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const doctors = ref<Doctor[]>([])
const slots = ref<ScheduleSlot[]>([])
const form = reactive({ doctorId: '', date: '', startTime: '', endTime: '', capacity: 1, fee: 0 })
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => {
  [doctors.value, slots.value] = await Promise.all([listDoctors(), listHospitalScheduleSlots()])
})
async function submit() {
  if (!form.doctorId || !form.date || !form.startTime || !form.endTime || form.endTime <= form.startTime || !Number.isInteger(form.capacity) || form.capacity < 1 || !Number.isFinite(form.fee) || form.fee < 0) {
    error.value = '请选择医生和日期，结束时间须晚于开始时间，容量须为正整数且诊费不能为负'; return
  }
  if (!window.confirm('确认发布该号源？发布后患者即可预约。')) return
  await perform(async () => { slots.value.unshift(await publishScheduleSlot({ ...form })) }, '号源发布成功')
}
async function toggleStatus(slot: ScheduleSlot) {
  const status = slot.status === 'PUBLISHED' ? 'DISABLED' : 'PUBLISHED'
  if (!window.confirm(`确认${status === 'DISABLED' ? '停用' : '重新启用'}该号源？`)) return
  await perform(async () => {
    const updated = await changeScheduleSlotStatus(slot.id, status)
    slots.value = slots.value.map(item => item.id === slot.id ? updated : item)
  }, '号源状态已更新')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">HOSPITAL / SCHEDULE</p><h2>排班号源</h2><p class="muted">为本院已启用医生发布就诊时段。</p></header>
    <WorkflowFeedback :error="error" :message="message" />
    <form @submit.prevent="submit"><fieldset :disabled="loading || busy"><legend>发布号源</legend><div class="form-grid">
      <label>医生<select v-model="form.doctorId" name="doctorId" required><option value="" disabled>选择医生</option><option v-for="doctor in doctors.filter(item => item.status === 'ACTIVE')" :key="doctor.id" :value="doctor.id">{{ doctor.displayName }} · {{ doctor.title }}</option></select></label>
      <label>日期<input v-model="form.date" name="date" type="date" required /></label><label>开始时间<input v-model="form.startTime" name="startTime" type="time" required /></label><label>结束时间<input v-model="form.endTime" name="endTime" type="time" required /></label>
      <label>容量<input v-model.number="form.capacity" name="capacity" type="number" min="1" step="1" required /></label><label>诊费（元）<input v-model.number="form.fee" name="fee" type="number" min="0" step="0.01" required /></label>
    </div><button class="primary">发布号源</button></fieldset></form>
    <button :disabled="loading || busy" @click="refresh">刷新可选医生</button>
    <AsyncState :loading="loading" :empty="!doctors.length" empty-text="暂无可选医生，请先创建医生" />
    <h3>本院排班历史</h3>
    <p class="muted">包含已停用和过期号源；已有预约的号源仅可停用，不会删除历史。</p>
    <AsyncState :loading="loading" :empty="!slots.length" empty-text="暂无排班号源"><div class="table-scroll"><table><thead><tr><th>号源 / 医生</th><th>就诊时段</th><th>剩余 / 容量</th><th>诊费</th><th>状态 / 操作</th></tr></thead><tbody><tr v-for="slot in slots" :key="slot.id"><td>{{ slot.id }}<small>{{ slot.doctorName }} · {{ slot.departmentName }}</small></td><td>{{ slot.date }}<small>{{ slot.startTime }}–{{ slot.endTime }}</small></td><td>{{ slot.remainingCapacity }} / {{ slot.capacity }}</td><td>{{ money(slot.fee) }}</td><td><StatusTag :status="slot.status" /> <button data-test="slot-status" :disabled="busy" @click="toggleStatus(slot)">{{ slot.status === 'PUBLISHED' ? '停用' : '启用' }}</button></td></tr></tbody></table></div></AsyncState>
  </section>
</template>
