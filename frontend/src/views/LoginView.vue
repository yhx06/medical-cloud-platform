<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const username = ref('patient_demo')
const password = ref('Demo@123456')
const error = ref('')
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()
async function submit() {
  loading.value = true; error.value = ''
  try { await auth.login(username.value, password.value); await router.push('/dashboard') }
  catch (reason: any) { error.value = reason.response?.data?.message ?? '登录失败，请检查账号和服务状态' }
  finally { loading.value = false }
}
</script>

<template><main class="login-page"><form class="login-card" @submit.prevent="submit">
  <p class="eyebrow">MEDICAL CLOUD</p><h1>医疗云服务平台</h1><p class="muted">课程项目演示环境</p>
  <label>账号<input v-model="username" data-test="username" autocomplete="username" /></label>
  <label>密码<input v-model="password" data-test="password" type="password" autocomplete="current-password" /></label>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <button :disabled="loading">{{ loading ? '正在登录…' : '登录' }}</button>
</form></main></template>

<style scoped>.login-page{min-height:100vh;display:grid;place-items:center;background:#eef4f8}.login-card{width:min(380px,calc(100% - 40px));padding:40px;border-radius:20px;background:white;box-shadow:0 20px 60px #183b5520;display:grid;gap:16px}.eyebrow{color:#1677a6;font-weight:700;letter-spacing:.15em}.muted{color:#687782}label{display:grid;gap:6px;color:#34434d}input{padding:12px;border:1px solid #cad6dc;border-radius:8px;font:inherit}button{padding:12px;border:0;border-radius:8px;background:#087e8b;color:white;font-weight:700}.error{color:#b42318}</style>
