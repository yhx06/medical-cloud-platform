<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listTenants, createTenant, createHospitalAdmin, changeTenantStatus, changeAccountStatus, listHospitalAdministrators } from '../../api/administration'
import type { Account, Tenant } from '../../types/business'
import { useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const tenants = ref<Tenant[]>([])
const accounts = ref<Account[]>([])
const name = ref('')
const admin = reactive({ tenantId: '', username: '', password: '' })
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => {
  tenants.value = await listTenants()
  accounts.value = (await Promise.all(tenants.value.map(tenant => listHospitalAdministrators(tenant.id)))).flat()
})
async function addTenant() {
  if (!name.value.trim()) { error.value = '请填写医院名称'; return }
  await perform(async () => { tenants.value.push(await createTenant(name.value.trim())); name.value = '' }, '医院创建成功')
}
async function addAdmin() {
  if (!admin.tenantId || !admin.username.trim() || admin.password.length < 8 || admin.password.length > 72) { error.value = '请选择医院并填写账号及 8–72 位初始密码'; return }
  await perform(async () => {
    accounts.value.push(await createHospitalAdmin(admin.tenantId, admin.username.trim(), admin.password))
    admin.username = ''; admin.password = ''
  }, '医院管理员创建成功')
}
async function toggleTenant(item: Tenant) {
  const status = item.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (!window.confirm('确认' + (status === 'DISABLED' ? '停用' : '启用') + '医院“' + item.name + '”？停用将阻止该院办理业务。')) return
  await perform(async () => { const updated = await changeTenantStatus(item.id, status); tenants.value = tenants.value.map(row => row.id === item.id ? updated : row) }, '医院状态已更新')
}
async function toggleAccount(item: Account) {
  const status = item.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (!window.confirm('确认' + (status === 'DISABLED' ? '停用' : '启用') + '账号“' + item.username + '”？')) return
  await perform(async () => { const updated = await changeAccountStatus(item.id, status); accounts.value = accounts.value.map(row => row.id === item.id ? updated : row) }, '账号状态已更新')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">PLATFORM / TENANTS</p><h2>租户管理</h2><p class="muted">维护医院和医院管理员账号。</p></header>
    <WorkflowFeedback :error="error" :message="message" />
    <form data-test="tenant-form" @submit.prevent="addTenant"><fieldset :disabled="loading || busy"><legend>新增医院</legend><div class="form-grid"><label>医院名称<input v-model="name" name="tenant-name" maxlength="100" required /></label><button class="primary">创建医院</button></div></fieldset></form>
    <button :disabled="loading || busy" @click="refresh">刷新医院</button>
    <AsyncState :loading="loading" :empty="!tenants.length" empty-text="暂无医院，请先创建医院"><div class="table-scroll"><table><thead><tr><th>医院</th><th>租户编号</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="tenant in tenants" :key="tenant.id"><td>{{ tenant.name }}</td><td>{{ tenant.id }}</td><td><StatusTag :status="tenant.status" /></td><td><button data-test="tenant-status" :disabled="busy" @click="toggleTenant(tenant)">{{ tenant.status === 'ACTIVE' ? '停用' : '启用' }}</button></td></tr></tbody></table></div></AsyncState>
    <form data-test="admin-form" @submit.prevent="addAdmin"><fieldset :disabled="loading || busy"><legend>创建医院管理员</legend><div class="form-grid">
      <label>医院<select v-model="admin.tenantId" name="tenantId" required><option value="" disabled>选择医院</option><option v-for="tenant in tenants.filter(item => item.status === 'ACTIVE')" :key="tenant.id" :value="tenant.id">{{ tenant.name }}</option></select></label>
      <label>登录账号<input v-model="admin.username" name="username" maxlength="64" autocomplete="off" required /></label><label>初始密码<input v-model="admin.password" name="password" type="password" minlength="8" maxlength="72" autocomplete="new-password" required /></label>
    </div><button class="primary">创建管理员</button></fieldset></form>
    <h3>医院管理员账号</h3>
    <p class="notice">列表来自服务端持久数据，刷新后仍可恢复；平台端仅展示账号与租户信息。</p>
    <AsyncState :empty="!accounts.length" empty-text="暂无医院管理员"><div class="table-scroll"><table><thead><tr><th>账号 / 编号</th><th>医院编号</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="account in accounts" :key="account.id"><td>{{ account.username }}<small>{{ account.id }}</small></td><td>{{ account.tenantId }}</td><td><StatusTag :status="account.status" /></td><td><button data-test="account-status" :disabled="busy" @click="toggleAccount(account)">{{ account.status === 'ACTIVE' ? '停用' : '启用' }}</button></td></tr></tbody></table></div></AsyncState>
  </section>
</template>
