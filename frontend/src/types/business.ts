import type { UserRole } from './auth'

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

export interface ApiError {
  code: string
  message: string
  requestId: string
  fieldErrors?: Record<string, string>
}

export type ActiveStatus = 'ACTIVE' | 'DISABLED'
export type ScheduleSlotStatus = 'PUBLISHED' | 'DISABLED'
export type AppointmentStatus = 'BOOKED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export type ConsultationStatus = 'IN_PROGRESS' | 'COMPLETED'
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED'

export interface ScheduleSlot {
  id: string
  tenantId: string
  tenantName: string
  departmentId: string
  departmentName: string
  doctorId: string
  doctorName: string
  date: string
  startTime: string
  endTime: string
  capacity: number
  remainingCapacity: number
  fee: number
  status: ScheduleSlotStatus
  createdAt: string
}

export interface Appointment {
  id: string
  tenantId: string
  patientId: string
  doctorId: string
  departmentId: string
  slotId: string
  date: string
  startTime: string
  endTime: string
  fee: number
  status: AppointmentStatus
  createdAt: string
  updatedAt: string
}

export interface PrescriptionItem {
  id: string
  sequenceNumber: number
  medicationName: string
  dosage: string
  frequency: string
  durationDays: number
}

export interface RecordCorrection {
  id: string
  sequenceNumber: number
  doctorId: string
  reason: string
  createdAt: string
}

export interface MedicalRecord {
  id: string
  appointmentId: string
  tenantId: string
  patientId: string
  doctorId: string
  status: ConsultationStatus
  complaint: string | null
  diagnosis: string | null
  advice: string | null
  prescriptionItems: PrescriptionItem[]
  corrections: RecordCorrection[]
  draftSavedAt: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface PrescriptionItemInput {
  medicationName: string
  dosage: string
  frequency: string
  durationDays: number
}

export interface ConsultationDraft {
  complaint?: string | null
  diagnosis?: string | null
  advice?: string | null
  prescriptionItems?: PrescriptionItemInput[] | null
}

export interface MedicalOrder {
  id: string
  consultationId: string
  appointmentId: string
  amount: number
  status: OrderStatus
  transactionNo: string | null
  paidAt: string | null
  createdAt: string
  updatedAt: string
}

export interface Tenant {
  id: string
  name: string
  status: ActiveStatus
}

export interface Account {
  id: string
  username: string
  role: UserRole
  status: ActiveStatus
  tenantId: string | null
}

export interface HospitalAccount extends Account {
  role: 'HOSPITAL_ADMIN'
  tenantId: string
}

export interface Department {
  id: string
  tenantId: string
  name: string
  description: string | null
  status: ActiveStatus
  createdAt: string
  updatedAt: string
}

export interface Doctor {
  id: string
  username: string
  tenantId: string
  departmentId: string
  displayName: string
  title: string | null
  biography: string | null
  status: ActiveStatus
}

export interface MedicalFile {
  id: string
  recordId: string
  originalFilename: string
  mediaType: string
  sizeBytes: number
  createdAt: string
}

export interface DownloadUrl {
  url: string
  expiresAt: string
}

export interface HospitalStatistics {
  tenantId: string
  from: string
  to: string
  totalAppointmentCount: number
  cancelledAppointmentCount: number
  completedAppointmentCount: number
  doctorCompletedConsultationCount: number
  paidRevenue: number
}

export interface TenantStatistics {
  tenantId: string
  tenantStatus: ActiveStatus
  accountCount: number
  appointmentCount: number
  completedAppointmentCount: number
  paidRevenue: number
}

export interface PlatformStatistics {
  from: string
  to: string
  tenants: TenantStatistics[]
}

export interface CatalogQuery {
  tenantId?: string
  departmentId?: string
  date?: string
  page?: number
  size?: number
  sort?: string
}

export interface CreateDepartmentInput { name: string; description?: string }
export interface UpdateDepartmentInput { name: string; description?: string; status: ActiveStatus }
export interface CreateDoctorInput {
  username: string
  password: string
  departmentId: string
  displayName: string
  title?: string
  biography?: string
}
export interface PublishScheduleSlotInput {
  doctorId: string
  date: string
  startTime: string
  endTime: string
  capacity: number
  fee: number
}
