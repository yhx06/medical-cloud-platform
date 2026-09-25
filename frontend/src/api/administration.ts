import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type {
  Account, ActiveStatus, CreateDepartmentInput, CreateDoctorInput, Department, Doctor, HospitalAccount,
  PublishScheduleSlotInput, ScheduleSlot, Tenant, UpdateDepartmentInput
} from '../types/business'

export async function listTenants(): Promise<Tenant[]> {
  return (await http.get<ApiEnvelope<Tenant[]>>('/api/platform/tenants')).data.data
}

export async function createTenant(name: string): Promise<Tenant> {
  return (await http.post<ApiEnvelope<Tenant>>('/api/platform/tenants', { name })).data.data
}

export async function createHospitalAdmin(tenantId: string, username: string, password: string): Promise<HospitalAccount> {
  return (await http.post<ApiEnvelope<HospitalAccount>>(`/api/platform/tenants/${tenantId}/admins`, { username, password })).data.data
}

export async function changeTenantStatus(tenantId: string, status: ActiveStatus): Promise<Tenant> {
  return (await http.patch<ApiEnvelope<Tenant>>(`/api/platform/tenants/${tenantId}/status`, { status })).data.data
}

export async function changeAccountStatus(accountId: string, status: ActiveStatus): Promise<Account> {
  return (await http.patch<ApiEnvelope<Account>>(`/api/platform/accounts/${accountId}/status`, { status })).data.data
}

export async function getHospitalProfile(): Promise<Tenant> {
  return (await http.get<ApiEnvelope<Tenant>>('/api/hospital/profile')).data.data
}

export async function listDepartments(): Promise<Department[]> {
  return (await http.get<ApiEnvelope<Department[]>>('/api/hospital/departments')).data.data
}

export async function createDepartment(input: CreateDepartmentInput): Promise<Department> {
  return (await http.post<ApiEnvelope<Department>>('/api/hospital/departments', input)).data.data
}

export async function updateDepartment(id: string, input: UpdateDepartmentInput): Promise<Department> {
  return (await http.put<ApiEnvelope<Department>>(`/api/hospital/departments/${id}`, input)).data.data
}

export async function listDoctors(): Promise<Doctor[]> {
  return (await http.get<ApiEnvelope<Doctor[]>>('/api/hospital/doctors')).data.data
}

export async function createDoctor(input: CreateDoctorInput): Promise<Doctor> {
  return (await http.post<ApiEnvelope<Doctor>>('/api/hospital/doctors', input)).data.data
}

export async function changeDoctorStatus(id: string, status: ActiveStatus): Promise<Doctor> {
  return (await http.put<ApiEnvelope<Doctor>>(`/api/hospital/doctors/${id}/status`, { status })).data.data
}

export async function publishScheduleSlot(input: PublishScheduleSlotInput): Promise<ScheduleSlot> {
  return (await http.post<ApiEnvelope<ScheduleSlot>>('/api/hospital/schedule-slots', input)).data.data
}

export async function listHospitalScheduleSlots(): Promise<ScheduleSlot[]> {
  return (await http.get<ApiEnvelope<ScheduleSlot[]>>('/api/hospital/schedule-slots')).data.data
}

export async function changeScheduleSlotStatus(id: string, status: ScheduleSlot['status']): Promise<ScheduleSlot> {
  return (await http.put<ApiEnvelope<ScheduleSlot>>(`/api/hospital/schedule-slots/${id}`, { status })).data.data
}

export async function listHospitalAdministrators(tenantId: string): Promise<HospitalAccount[]> {
  return (await http.get<ApiEnvelope<HospitalAccount[]>>(`/api/platform/tenants/${tenantId}/administrators`)).data.data
}
