import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { CatalogDepartment, CatalogQuery, CatalogTenant, PageResponse, ScheduleSlot } from '../types/business'

export async function listCatalogTenants(): Promise<CatalogTenant[]> {
  return (await http.get<ApiEnvelope<CatalogTenant[]>>('/api/catalog/tenants')).data.data
}

export async function listCatalogDepartments(tenantId: string): Promise<CatalogDepartment[]> {
  return (await http.get<ApiEnvelope<CatalogDepartment[]>>(`/api/catalog/tenants/${tenantId}/departments`)).data.data
}

export async function searchScheduleSlots(params: CatalogQuery = {}): Promise<PageResponse<ScheduleSlot>> {
  return (await http.get<ApiEnvelope<PageResponse<ScheduleSlot>>>('/api/catalog/schedule-slots', { params })).data.data
}
