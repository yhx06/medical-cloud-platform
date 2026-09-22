<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getHospitalProfile, listDepartments, createDepartment, updateDepartment } from '../../api/administration'
import type { Department, Tenant, ActiveStatus } from '../../types/business'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const profile = ref<Tenant>()
const items = ref<Department[]>([])
const editing = ref<Department>()
const form = reactive({ name: '', description: '', status: 'ACTIVE' as ActiveStatus })
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { [profile.value, items.value] = await Promise.all([getHospitalProfile(), listDepartments()]) })
function reset() { editing.value = undefined; Object.assign(form, { name: '', description: '', status: 'ACTIVE' }) }
function edit(item: Department) { editing.value = item; Object.assign(form, { name: item.name, description: item.description ?? '', status: item.status }) }
async function submit() {
  if (!form.name.trim()) { error.value = '请填写科室名称'; return }
  if (editing.value && !window.confirm('确认更新该科室资料和状态？停用后将影响可用号源。')) return
  await perform(async () => {
    const data = { name: form.name.trim(), description: form.description.trim() }
    const updated = editing.value ? await updateDepartment(editing.value.id, { ...data, status: form.status }) : await createDepartment(data)
    items.value = editing.value ? items.value.map(item => item.id === updated.id ? updated : item) : [...items.value, updated]
    reset()
  }, '科室保存成功')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">HOSPITAL / DEPARTMENTS</p><h2>科室管理</h2><p v-if="profile">{{ profile.name }} <StatusTag :status="profile.status" /></p><p class="muted">维护本院科室，停用不会删除历史数据。</p></header>
    <WorkflowFeedback :error="error" :message="message" />
    <form data-test="department-form" @submit.prevent="submit"><fieldset :disabled="loading || busy">
      <legend>{{ editing ? '编辑科室' : '新增科室' }}</legend><div class="form-grid">
      <label>科室名称<input v-model="form.name" name="department-name" maxlength="100" required /></label>
      <label>科室简介<textarea v-model="form.description" maxlength="500" rows="3" /></label>
      <label v-if="editing">状态<select v-model="form.status" name="department-status"><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select></label></div>
      <div class="actions"><button class="primary">保存科室</button><button v-if="editing" type="button" @click="reset">取消编辑</button></div>
    </fieldset></form>
    <button :disabled="loading || busy" @click="refresh">刷新科室</button>
    <AsyncState :loading="loading" :empty="!items.length" empty-text="暂无科室，请先新增科室"><div class="table-scroll"><table><thead><tr><th>科室</th><th>简介</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ item.name }}<small>{{ item.id }}</small></td><td>{{ item.description || '—' }}</td><td><StatusTag :status="item.status" /></td><td><button data-test="edit-department" :disabled="busy" @click="edit(item)">编辑</button></td></tr></tbody></table></div></AsyncState>
  </section>
</template>
