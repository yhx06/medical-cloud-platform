import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { ConsultationDraft, MedicalRecord } from '../types/business'

export async function listPatientRecords(): Promise<MedicalRecord[]> {
  return (await http.get<ApiEnvelope<MedicalRecord[]>>('/api/patient/records')).data.data
}

export async function getPatientRecord(recordId: string): Promise<MedicalRecord> {
  return (await http.get<ApiEnvelope<MedicalRecord>>(`/api/patient/records/${recordId}`)).data.data
}

export async function startConsultation(appointmentId: string): Promise<MedicalRecord> {
  return (await http.post<ApiEnvelope<MedicalRecord>>(`/api/doctor/appointments/${appointmentId}/start`)).data.data
}

export async function saveConsultationDraft(appointmentId: string, draft: ConsultationDraft): Promise<MedicalRecord> {
  return (await http.put<ApiEnvelope<MedicalRecord>>(`/api/doctor/consultations/${appointmentId}/draft`, draft)).data.data
}

export async function completeConsultation(appointmentId: string): Promise<MedicalRecord> {
  return (await http.post<ApiEnvelope<MedicalRecord>>(`/api/doctor/consultations/${appointmentId}/complete`)).data.data
}

export async function appendRecordCorrection(recordId: string, reason: string): Promise<MedicalRecord> {
  return (await http.post<ApiEnvelope<MedicalRecord>>(`/api/doctor/records/${recordId}/corrections`, { reason })).data.data
}
