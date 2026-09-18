import axios from 'axios'
import type { ApiError } from '../types/business'

export const TOKEN_KEY = 'medical-cloud-token'
export const http = axios.create()

http.interceptors.request.use(config => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) sessionStorage.removeItem(TOKEN_KEY)
  return Promise.reject(error)
})

export function getApiErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  const responseData = (error as { response?: { data?: unknown } })?.response?.data
  if (responseData && typeof responseData === 'object') {
    const apiError = responseData as Partial<ApiError>
    if (typeof apiError.message === 'string') {
      const details = apiError.fieldErrors && Object.entries(apiError.fieldErrors)
        .map(([field, message]) => `${field}：${message}`)
        .join('，')
      return details ? `${apiError.message}（${details}）` : apiError.message
    }
  }
  return error instanceof Error && error.message ? error.message : fallback
}
