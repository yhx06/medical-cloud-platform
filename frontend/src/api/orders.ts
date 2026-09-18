import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { MedicalOrder } from '../types/business'

export async function listPatientOrders(): Promise<MedicalOrder[]> {
  return (await http.get<ApiEnvelope<MedicalOrder[]>>('/api/patient/orders')).data.data
}

export async function getPatientOrder(id: string): Promise<MedicalOrder> {
  return (await http.get<ApiEnvelope<MedicalOrder>>(`/api/patient/orders/${id}`)).data.data
}

export async function payPatientOrder(id: string): Promise<MedicalOrder> {
  return (await http.post<ApiEnvelope<MedicalOrder>>(`/api/patient/orders/${id}/pay`)).data.data
}
