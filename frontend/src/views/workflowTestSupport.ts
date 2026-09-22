import { beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../types/auth'
import type { Appointment, MedicalRecord, MedicalOrder, ScheduleSlot, Department, Doctor } from '../types/business'

export const slot: ScheduleSlot = { id: 'slot-a', tenantId: 'tenant-a', tenantName: '演示医院', departmentId: 'department-a', departmentName: '内科', doctorId: 'doctor-a', doctorName: '李医生', date: '2026-10-01', startTime: '09:00:00', endTime: '10:00:00', capacity: 3, remainingCapacity: 2, fee: 25, status: 'PUBLISHED', createdAt: '2026-09-22T00:00:00Z' }
export const appointment: Appointment = { id: 'appointment-a', tenantId: 'tenant-a', patientId: 'patient-a', doctorId: 'doctor-a', departmentId: 'department-a', slotId: 'slot-a', date: slot.date, startTime: slot.startTime, endTime: slot.endTime, fee: 25, status: 'BOOKED', createdAt: slot.createdAt, updatedAt: slot.createdAt }
export const record: MedicalRecord = { id: 'record-a', appointmentId: 'appointment-a', tenantId: 'tenant-a', patientId: 'patient-a', doctorId: 'doctor-a', status: 'IN_PROGRESS', complaint: '头痛', diagnosis: '偏头痛', advice: '休息', prescriptionItems: [], corrections: [], draftSavedAt: null, completedAt: null, createdAt: slot.createdAt, updatedAt: slot.createdAt }
export const order: MedicalOrder = { id: 'order-a', consultationId: 'record-a', appointmentId: 'appointment-a', amount: 25, status: 'PENDING', transactionNo: null, paidAt: null, createdAt: slot.createdAt, updatedAt: slot.createdAt }
export const department: Department = { id: 'department-a', tenantId: 'tenant-a', name: '内科', description: '普通内科', status: 'ACTIVE', createdAt: slot.createdAt, updatedAt: slot.createdAt }
export const doctor: Doctor = { id: 'doctor-a', username: 'doctor_demo', tenantId: 'tenant-a', departmentId: department.id, displayName: '李医生', title: '主治医师', biography: '演示资料', status: 'ACTIVE' }
export const page = (content = [slot], number = 0, totalElements = 1) => ({ content, number, size: 10, totalElements, totalPages: Math.ceil(totalElements / 10) })
export const hospitalStats = { tenantId: 'tenant-a', from: '2026-09-01', to: '2026-09-22', totalAppointmentCount: 12, cancelledAppointmentCount: 2, completedAppointmentCount: 7, doctorCompletedConsultationCount: 7, paidRevenue: 175 }
export const platformStats = { from: '2026-09-01', to: '2026-09-22', tenants: [{ tenantId: 'tenant-a', tenantStatus: 'ACTIVE', accountCount: 9, appointmentCount: 12, completedAppointmentCount: 7, paidRevenue: 175 }] }
export function setupWorkflow(role: UserRole) {
  let mock: MockAdapter
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().user = { userId: role === 'DOCTOR' ? 'doctor-a' : 'patient-a', username: 'demo', role, tenantId: role === 'PATIENT' ? null : 'tenant-a' }
    mock = new MockAdapter(http)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => { mock.restore(); vi.restoreAllMocks() })
  return () => mock
}
