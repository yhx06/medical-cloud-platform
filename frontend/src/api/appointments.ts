import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { Appointment } from '../types/business'

export async function bookAppointment(slotId: string): Promise<Appointment> {
  return (await http.post<ApiEnvelope<Appointment>>('/api/patient/appointments', { slotId })).data.data
}

export async function listPatientAppointments(): Promise<Appointment[]> {
  return (await http.get<ApiEnvelope<Appointment[]>>('/api/patient/appointments')).data.data
}

export async function getPatientAppointment(id: string): Promise<Appointment> {
  return (await http.get<ApiEnvelope<Appointment>>(`/api/patient/appointments/${id}`)).data.data
}

export async function cancelAppointment(id: string): Promise<Appointment> {
  return (await http.post<ApiEnvelope<Appointment>>(`/api/patient/appointments/${id}/cancel`)).data.data
}

export async function listDoctorAppointments(): Promise<Appointment[]> {
  return (await http.get<ApiEnvelope<Appointment[]>>('/api/doctor/appointments')).data.data
}
