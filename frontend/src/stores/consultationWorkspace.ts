import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from './auth'
import type { MedicalRecord } from '../types/business'

// Only server-returned records from this browser session are kept. No clinical data is persisted.
export const useConsultationWorkspace = defineStore('consultation-workspace', () => {
  const auth = useAuthStore()
  const sessionRecords = ref<MedicalRecord[]>([])
  watch(() => [auth.user?.userId, auth.tenantId, auth.role], () => { sessionRecords.value = [] }, { flush: 'sync' })
  const records = computed(() => sessionRecords.value.filter(record =>
    auth.role === 'DOCTOR' && record.doctorId === auth.user?.userId && record.tenantId === auth.tenantId))
  function remember(record: MedicalRecord) {
    if (auth.role !== 'DOCTOR' || record.doctorId !== auth.user?.userId || record.tenantId !== auth.tenantId) return
    sessionRecords.value = [...sessionRecords.value.filter(item => item.id !== record.id), record]
  }
  return { records, remember }
})
