import { http } from './http'
import type { ApiEnvelope, AuthUser, LoginResponse } from '../types/auth'

export async function login(username: string, password: string): Promise<LoginResponse> {
  return (await http.post<ApiEnvelope<LoginResponse>>('/api/auth/login', { username, password })).data.data
}

export async function currentUser(): Promise<AuthUser> {
  return (await http.get<ApiEnvelope<AuthUser>>('/api/me')).data.data
}
