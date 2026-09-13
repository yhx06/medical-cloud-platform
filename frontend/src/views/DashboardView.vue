<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
</script>
<template><section><p class="eyebrow">ROLE WORKSPACE</p><h2>欢迎，{{ auth.user?.username }}</h2><p>当前角色：{{ auth.role }}</p><p>这里展示各角色的项目入口，业务模块将在后续迭代接入。</p></section></template>
<style scoped>section{background:white;padding:32px;border-radius:16px}.eyebrow{color:#087e8b;font-weight:700;letter-spacing:.12em}</style>
