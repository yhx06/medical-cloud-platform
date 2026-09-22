<script setup lang="ts">
import { ref, watch } from 'vue'
import { listRecordFiles, uploadRecordFile, createFileDownloadUrl } from '../api/files'
import type { MedicalFile, DownloadUrl } from '../types/business'
import { useWorkflow } from '../composables/useWorkflow'
import AsyncState from './AsyncState.vue'
import WorkflowFeedback from './WorkflowFeedback.vue'

const props = defineProps<{ recordId: string; allowUpload?: boolean }>()
const files = ref<MedicalFile[]>([])
const links = ref<Record<string, DownloadUrl>>({})
const { loading, busy, error, message, load, perform } = useWorkflow()
async function refresh() { await load(async () => { files.value = await listRecordFiles(props.recordId) }) }
watch(() => props.recordId, () => { files.value = []; links.value = {}; void refresh() }, { immediate: true })
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || busy.value) return
  const extension = file.name.split('.').pop()?.toLowerCase()
  const types: Record<string, string> = { pdf: 'application/pdf', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png' }
  if (!extension || !types[extension] || file.type !== types[extension]) {
    error.value = '仅支持 PDF、JPG、PNG 文件'; input.value = ''; return
  }
  if (file.size > 10 * 1024 * 1024 || file.size === 0) {
    error.value = '文件不能为空且不得超过 10 MiB'; input.value = ''; return
  }
  await perform(async () => { files.value.push(await uploadRecordFile(props.recordId, file)) }, '附件上传成功')
  input.value = ''
}
async function download(file: MedicalFile) {
  await perform(async () => { links.value[file.id] = await createFileDownloadUrl(file.id) }, '下载地址已生成，请在有效期内打开')
}
</script>
<template>
  <section class="subsection">
    <h3>病历附件</h3>
    <template v-if="allowUpload">
      <p id="file-limits" class="muted">支持 PDF/JPG/PNG，单文件最大 10 MiB。上传后由服务端继续校验。</p>
      <label>选择附件<input type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" aria-describedby="file-limits" :disabled="busy || loading" @change="upload" /></label>
    </template>
    <WorkflowFeedback :error="error" :message="message" />
    <button type="button" :disabled="loading || busy" @click="refresh">刷新附件</button>
    <AsyncState :loading="loading" :empty="!files.length" empty-text="暂无附件">
      <ul class="file-list"><li v-for="file in files" :key="file.id">
        <span>{{ file.originalFilename }} · {{ (file.sizeBytes / 1024).toFixed(1) }} KiB</span>
        <button type="button" data-test="download-file" :disabled="busy" @click="download(file)">获取下载地址</button>
        <template v-if="links[file.id]"><a data-test="file-link" :href="links[file.id]!.url" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer">打开附件</a><small>有效至 {{ links[file.id]!.expiresAt }}</small></template>
      </li></ul>
    </AsyncState>
  </section>
</template>
