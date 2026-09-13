<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { menusFor } from '../navigation/roleMenus'
const auth = useAuthStore(); const router = useRouter()
const menus = computed(() => auth.role ? menusFor(auth.role) : [])
function logout(){ auth.logout(); router.push('/login') }
</script>
<template><div class="shell"><aside><h1>医疗云平台</h1><nav><RouterLink v-for="item in menus" :key="item.key" :to="item.path">{{ item.label }}</RouterLink></nav></aside><main><header><span>{{ auth.user?.username }}</span><button @click="logout">退出</button></header><RouterView /></main></div></template>
<style scoped>.shell{min-height:100vh;display:grid;grid-template-columns:240px 1fr;background:#f4f7f8}aside{padding:28px;background:#123c4a;color:white}nav{display:grid;gap:8px;margin-top:32px}a{color:#dcebed;text-decoration:none;padding:10px;border-radius:8px}.router-link-active{background:#ffffff1f;color:white}main{padding:24px}header{display:flex;justify-content:flex-end;gap:16px;margin-bottom:28px}button{border:0;background:white;padding:8px 14px;border-radius:8px}</style>
