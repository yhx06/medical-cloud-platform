<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listDoctors, publishScheduleSlot } from '../../api/administration'
import { searchScheduleSlots } from '../../api/catalog'
import { useAuthStore } from '../../stores/auth'
import type { Doctor, ScheduleSlot, PageResponse } from '../../types/business'
import { money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import PaginationBar from '../../components/PaginationBar.vue'
const auth = useAuthStore()
const catalog = ref<PageResponse<ScheduleSlot>>({ content: [], number: 0, size: 10, totalElements: 0, totalPages: 0 })
const catalogDate = ref('')
const { loading: catalogLoading, error: catalogError, load: loadCatalog } = useWorkflow()
async function search(page = 0) {
  if (!auth.tenantId) { catalogError.value = '当前账号没有所属医院'; return }
  await loadCatalog(async () => {
    catalog.value = await searchScheduleSlots({ tenantId: auth.tenantId!, date: catalogDate.value || undefined, page, size: 10 })
  })
}
const doctors = ref<Doctor[]>([])
const published = ref<ScheduleSlot[]>([])
const form = reactive({ doctorId: '', date: '', startTime: '', endTime: '', capacity: 1, fee: 0 })
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { doctors.value = await listDoctors() })
async function submit() {
  if (!form.doctorId || !form.date || !form.startTime || !form.endTime || form.endTime <= form.startTime || !Number.isInteger(form.capacity) || form.capacity < 1 || !Number.isFinite(form.fee) || form.fee < 0) {
    error.value = '请选择医生和日期，结束时间须晚于开始时间，容量须为正整数且诊费不能为负'; return
  }
  if (!window.confirm('确认发布该号源？发布后患者即可预约。')) return
  await perform(async () => { published.value.unshift(await publishScheduleSlot({ ...form })) }, '号源发布成功')
}
onMounted(() => { void refresh(); void search() })
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
    <h3>本院当前可预约号源</h3>
    <p class="muted">目录仅显示当前可预约的号源，不包含过期、停用号源。</p>
    <div class="form-grid"><label>查询日期<input v-model="catalogDate" type="date" /></label><button :disabled="catalogLoading || busy" @click="search(0)">查询本院号源</button></div>
    <WorkflowFeedback :error="catalogError" />
    <AsyncState :loading="catalogLoading" :empty="!catalog.content.length" empty-text="暂无可预约号源"><div class="table-scroll"><table><thead><tr><th>医生 / 科室</th><th>日期 / 时段</th><th>剩余 / 容量</th><th>诊费</th></tr></thead><tbody><tr v-for="slot in catalog.content" :key="slot.id"><td>{{ slot.doctorName }}<small>{{ slot.departmentName }}</small></td><td>{{ slot.date }}<small>{{ slot.startTime }}–{{ slot.endTime }}</small></td><td>{{ slot.remainingCapacity }} / {{ slot.capacity }}</td><td>{{ money(slot.fee) }}</td></tr></tbody></table></div></AsyncState>
    <PaginationBar v-if="catalog.totalElements" :page="catalog.number" :size="catalog.size" :total-elements="catalog.totalElements" @change="search" />
    <h3>本次会话发布结果</h3>
    <p class="notice">当前医院接口未提供完整历史排班列表和编辑能力；此处仅保留本次会话的发布结果，刷新浏览器后不保留。当前可预约号源可在上方目录重新查询。</p>
    <AsyncState :empty="!published.length" empty-text="本次会话暂无发布结果"><div class="table-scroll"><table><thead><tr><th>号源 / 医生</th><th>就诊时段</th><th>容量</th><th>诊费</th><th>状态</th></tr></thead><tbody><tr v-for="slot in published" :key="slot.id"><td>{{ slot.id }}<small>{{ slot.doctorName }}</small></td><td>{{ slot.date }}<small>{{ slot.startTime }}–{{ slot.endTime }}</small></td><td>{{ slot.capacity }}</td><td>{{ money(slot.fee) }}</td><td><StatusTag :status="slot.status" /></td></tr></tbody></table></div></AsyncState>
  </section>
</template>
