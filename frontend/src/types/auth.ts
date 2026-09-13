export type UserRole = 'PATIENT' | 'DOCTOR' | 'HOSPITAL_ADMIN' | 'PLATFORM_ADMIN'

export interface AuthUser {
  userId: string
  username: string
  role: UserRole
  tenantId: string | null
}

export interface LoginResponse { accessToken: string; user: AuthUser }
export interface ApiEnvelope<T> { data: T; requestId?: string }
