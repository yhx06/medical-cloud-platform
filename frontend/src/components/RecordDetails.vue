<script setup lang="ts">
import type { MedicalRecord } from '../types/business'
import StatusTag from './StatusTag.vue'
defineProps<{ record: MedicalRecord }>()
</script>
<template>
  <article class="record-detail">
    <h3>病历详情 <StatusTag :status="record.status" /></h3>
    <p class="muted">病历编号：{{ record.id }} · 预约：{{ record.appointmentId }}</p>
    <dl><dt>主诉</dt><dd>{{ record.complaint || '尚未填写' }}</dd><dt>诊断</dt><dd>{{ record.diagnosis || '尚未填写' }}</dd><dt>处理建议</dt><dd>{{ record.advice || '尚未填写' }}</dd></dl>
    <h4>处方</h4>
    <p v-if="!record.prescriptionItems.length" class="muted">暂无处方</p>
    <ul v-else><li v-for="item in record.prescriptionItems" :key="item.id">{{ item.medicationName }} · {{ item.dosage }} · {{ item.frequency }} · {{ item.durationDays }} 天</li></ul>
    <h4>更正记录</h4>
    <p v-if="!record.corrections.length" class="muted">暂无更正</p>
    <ol v-else><li v-for="item in record.corrections" :key="item.id">{{ item.reason }} <small>{{ item.createdAt }} · 医生 {{ item.doctorId }}</small></li></ol>
    <p v-if="record.completedAt" class="muted">完成时间：{{ record.completedAt }}</p>
  </article>
</template>
