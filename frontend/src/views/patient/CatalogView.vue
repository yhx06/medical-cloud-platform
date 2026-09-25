<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { listCatalogDepartments, listCatalogTenants, searchScheduleSlots } from '../../api/catalog'
import { bookAppointment } from '../../api/appointments'
import type { CatalogDepartment, CatalogTenant, PageResponse, ScheduleSlot } from '../../types/business'
import { useWorkflow, money } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
import PaginationBar from '../../components/PaginationBar.vue'
import StatusTag from '../../components/StatusTag.vue'
const filters = reactive({ tenantId: '', departmentId: '', date: '' })
const tenants = ref<CatalogTenant[]>([])
const departments = ref<CatalogDepartment[]>([])
const page = ref(0)
const result = ref<PageResponse<ScheduleSlot>>({ content: [], number: 0, size: 10, totalElements: 0, totalPages: 0 })
const { loading, busy, error, message, load, perform } = useWorkflow()
async function search(next = 0) {
  await load(async () => {
    result.value = await searchScheduleSlots({ tenantId: filters.tenantId.trim() || undefined, departmentId: filters.departmentId.trim() || undefined, date: filters.date || undefined, page: next, size: 10 })
    page.value = next
  })
}
async function book(slot: ScheduleSlot) {
  await perform(async () => {
    await bookAppointment(slot.id)
    slot.remainingCapacity = Math.max(0, slot.remainingCapacity - 1)
  }, '预约成功，请在我的预约中查看')
}
watch(() => filters.tenantId, async tenantId => {
  filters.departmentId = ''
  departments.value = tenantId ? await listCatalogDepartments(tenantId) : []
})
onMounted(async () => {
  await load(async () => { tenants.value = await listCatalogTenants() })
  await search()
})
</script>
<template>
  <section class="workspace">
    <header><p class="eyebrow">PATIENT / CATALOG</p><h2>查找号源</h2><p class="muted">选择医院、科室和医生的可用就诊时段。</p></header>
    <form class="form-grid" @submit.prevent="search(0)">
      <label>医院<select v-model="filters.tenantId" name="tenantId"><option value="">全部医院</option><option v-for="tenant in tenants" :key="tenant.id" :value="tenant.id">{{ tenant.name }}</option></select></label>
      <label>科室<select v-model="filters.departmentId" name="departmentId" :disabled="!filters.tenantId"><option value="">全部科室</option><option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option></select></label>
      <label>就诊日期<input v-model="filters.date" name="date" type="date" /></label>
      <button :disabled="loading || busy">查询号源</button>
    </form>
    <WorkflowFeedback :error="error" :message="message" />
    <AsyncState :loading="loading" :empty="!result.content.length" empty-text="暂无符合条件的号源">
      <div class="table-scroll"><table><thead><tr><th>医院 / 科室</th><th>医生</th><th>就诊时段</th><th>剩余 / 容量</th><th>诊费</th><th>操作</th></tr></thead><tbody>
        <tr v-for="slot in result.content" :key="slot.id"><td>{{ slot.tenantName }}<small>{{ slot.departmentName }}</small></td><td>{{ slot.doctorName }}</td><td>{{ slot.date }}<small>{{ slot.startTime }}–{{ slot.endTime }}</small></td><td>{{ slot.remainingCapacity }} / {{ slot.capacity }}</td><td>{{ money(slot.fee) }}</td><td><StatusTag :status="slot.status" /> <button data-test="book-slot" :disabled="busy || slot.remainingCapacity <= 0 || slot.status !== 'PUBLISHED'" @click="book(slot)">预约</button></td></tr>
      </tbody></table></div>
    </AsyncState>
    <PaginationBar v-if="result.totalElements > 0" :page="page" :size="10" :total-elements="result.totalElements" @change="search" />
  </section>
</template>
