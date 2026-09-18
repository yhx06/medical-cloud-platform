import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { DownloadUrl, MedicalFile } from '../types/business'

export async function uploadRecordFile(recordId: string, file: File): Promise<MedicalFile> {
  const body = new FormData()
  body.append('file', file)
  return (await http.post<ApiEnvelope<MedicalFile>>(`/api/doctor/records/${recordId}/files`, body)).data.data
}

export async function listRecordFiles(recordId: string): Promise<MedicalFile[]> {
  return (await http.get<ApiEnvelope<MedicalFile[]>>(`/api/records/${recordId}/files`)).data.data
}

export async function createFileDownloadUrl(fileId: string): Promise<DownloadUrl> {
  return (await http.post<ApiEnvelope<DownloadUrl>>(`/api/files/${fileId}/download-url`)).data.data
}
