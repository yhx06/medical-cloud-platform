import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => { setActivePinia(createPinia()); sessionStorage.clear() })

  it('stores authenticated user and token after login', async () => {
    const mock = new MockAdapter(http)
    mock.onPost('/api/auth/login').reply(200, { data: {
      accessToken: 'signed-token',
      user: { userId: '1', username: 'doctor_a', role: 'DOCTOR', tenantId: 'tenant-a' }
    } })
    const auth = useAuthStore()
    await auth.login('doctor_a', 'Demo@123456')
    expect(auth.user?.role).toBe('DOCTOR')
    expect(auth.isAuthenticated).toBe(true)
    expect(sessionStorage.getItem('medical-cloud-token')).toBe('signed-token')
  })

  it('restores authority from the server instead of decoding the token', async () => {
    sessionStorage.setItem('medical-cloud-token', 'opaque-token')
    const mock = new MockAdapter(http)
    mock.onGet('/api/me').reply(200, { data: {
      userId: '1', username: 'patient_demo', role: 'PATIENT', tenantId: null
    } })
    const auth = useAuthStore()
    await auth.restore()
    expect(auth.role).toBe('PATIENT')
  })

  it('restores once per store lifecycle without leaking across Pinia instances', async () => {
    sessionStorage.setItem('medical-cloud-token', 'opaque-token')
    const mock = new MockAdapter(http)
    mock.onGet('/api/me').reply(200, { data: {
      userId: '1', username: 'patient_demo', role: 'PATIENT', tenantId: null
    } })

    const first = useAuthStore()
    await Promise.all([first.restoreOnce(), first.restoreOnce()])
    expect(mock.history.get).toHaveLength(1)

    setActivePinia(createPinia())
    const second = useAuthStore()
    await second.restoreOnce()
    expect(mock.history.get).toHaveLength(2)
  })
})
