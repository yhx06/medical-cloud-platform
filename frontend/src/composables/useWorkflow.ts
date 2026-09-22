import { ref } from 'vue'
import { getApiErrorMessage } from '../api/http'

export function useWorkflow() {
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const message = ref('')
  async function load(action: () => Promise<void>) {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try { await action() } catch (cause) { error.value = getApiErrorMessage(cause) }
    finally { loading.value = false }
  }
  async function perform(action: () => Promise<void>, success: string) {
    if (busy.value) return
    busy.value = true
    error.value = ''
    message.value = ''
    try { await action(); message.value = success }
    catch (cause) { error.value = getApiErrorMessage(cause) }
    finally { busy.value = false }
  }
  return { loading, busy, error, message, load, perform }
}

export const money = (value: number) => '¥' + value.toFixed(2)
export function currentDateRange() {
  const today = new Date()
  const localDate = (date: Date) => [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
  return { from: localDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: localDate(today) }
}
