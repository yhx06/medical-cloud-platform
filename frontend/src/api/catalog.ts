import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { CatalogQuery, PageResponse, ScheduleSlot } from '../types/business'

export async function searchScheduleSlots(params: CatalogQuery = {}): Promise<PageResponse<ScheduleSlot>> {
  return (await http.get<ApiEnvelope<PageResponse<ScheduleSlot>>>('/api/catalog/schedule-slots', { params })).data.data
}
