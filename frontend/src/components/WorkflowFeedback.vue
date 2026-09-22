<script setup lang="ts">
defineProps<{ error?: string; message?: string }>()
</script>
<template>
  <p v-if="error" role="alert" class="feedback error">{{ error }}</p>
  <p v-if="message" role="status" class="feedback success">{{ message }}</p>
</template>
