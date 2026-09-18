import { http } from './http'
import type { ApiEnvelope } from '../types/auth'
import type { HospitalStatistics, PlatformStatistics } from '../types/business'

const dateRange = (from: string, to: string) => ({ from, to })

export async function getHospitalStatistics(from: string, to: string): Promise<HospitalStatistics> {
  return (await http.get<ApiEnvelope<HospitalStatistics>>('/api/hospital/statistics', { params: dateRange(from, to) })).data.data
}

export async function getPlatformStatistics(from: string, to: string): Promise<PlatformStatistics> {
  return (await http.get<ApiEnvelope<PlatformStatistics>>('/api/platform/statistics', { params: dateRange(from, to) })).data.data
}
