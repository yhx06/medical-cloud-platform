<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listDepartments, listDoctors, createDoctor, changeDoctorStatus } from '../../api/administration'
import type { Department, Doctor } from '../../types/business'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const departments = ref<Department[]>([])
const doctors = ref<Doctor[]>([])
const form = reactive({ username: '', password: '', departmentId: '', displayName: '', title: '', biography: '' })
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { [departments.value, doctors.value] = await Promise.all([listDepartments(), listDoctors()]) })
async function submit() {
  if (!form.username.trim() || !form.displayName.trim() || !form.departmentId || form.password.length < 8 || form.password.length > 72) { error.value = '请填写账号、姓名、科室及 8–72 位初始密码'; return }
  await perform(async () => {
    doctors.value.push(await createDoctor({ ...form, username: form.username.trim(), displayName: form.displayName.trim() }))
    Object.assign(form, { username: '', password: '', departmentId: '', displayName: '', title: '', biography: '' })
  }, '医生账号创建成功')
}
async function toggle(doctor: Doctor) {
  const status = doctor.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (!window.confirm('确认' + (status === 'DISABLED' ? '停用' : '启用') + '医生“' + doctor.displayName + '”？')) return
  await perform(async () => { const updated = await changeDoctorStatus(doctor.id, status); doctors.value = doctors.value.map(item => item.id === doctor.id ? updated : item) }, '医生状态已更新')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">HOSPITAL / DOCTORS</p><h2>医生管理</h2><p class="muted">为本院科室创建医生账号和演示资料。</p></header>
    <WorkflowFeedback :error="error" :message="message" />
    <form @submit.prevent="submit"><fieldset :disabled="loading || busy"><legend>新增医生</legend><div class="form-grid">
      <label>登录账号<input v-model="form.username" name="username" maxlength="64" autocomplete="off" required /></label>
      <label>初始密码<input v-model="form.password" name="password" type="password" minlength="8" maxlength="72" autocomplete="new-password" required /></label>
      <label>医生姓名<input v-model="form.displayName" name="displayName" maxlength="100" required /></label>
      <label>所属科室<select v-model="form.departmentId" name="departmentId" required><option value="" disabled>选择已启用科室</option><option v-for="item in departments.filter(item => item.status === 'ACTIVE')" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label>职称<input v-model="form.title" maxlength="100" /></label><label>医生简介<textarea v-model="form.biography" maxlength="1000" rows="2" /></label>
    </div><button class="primary">创建医生</button></fieldset></form>
    <button :disabled="loading || busy" @click="refresh">刷新医生</button>
    <AsyncState :loading="loading" :empty="!doctors.length" empty-text="暂无医生"><div class="table-scroll"><table><thead><tr><th>医生 / 账号</th><th>科室</th><th>职称</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in doctors" :key="item.id"><td>{{ item.displayName }}<small>{{ item.username }}</small></td><td>{{ departments.find(dept => dept.id === item.departmentId)?.name || item.departmentId }}</td><td>{{ item.title || '—' }}</td><td><StatusTag :status="item.status" /></td><td><button data-test="doctor-status" :disabled="busy" @click="toggle(item)">{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button></td></tr></tbody></table></div></AsyncState>
  </section>
</template>
