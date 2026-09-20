import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { canAccess, router } from './index'
import { http, TOKEN_KEY } from '../api/http'

describe('route authorization', () => {
  it('redirects anonymous users from protected routes', () => {
    expect(canAccess(undefined, { requiresAuth: true })).toBe('/login')
  })
  it('redirects users outside a route role set', () => {
    expect(canAccess('PATIENT', { requiresAuth: true, roles: ['DOCTOR'] })).toBe('/dashboard')
  })
  it('allows an authorized role', () => {
    expect(canAccess('DOCTOR', { requiresAuth: true, roles: ['DOCTOR'] })).toBe(true)
  })

  it('redirects platform administrators away from patient record routes', () => {
    const patientRecordMeta = { requiresAuth: true, roles: ['PATIENT'] as const }
    expect(canAccess('PLATFORM_ADMIN', patientRecordMeta)).toBe('/dashboard')
  })

  it('registers the complete explicit role route matrix', () => {
    const rolesByPath = Object.fromEntries(router.getRoutes().map(route => [route.path, route.meta.roles]))

    expect(rolesByPath).toMatchObject({
      '/dashboard': ['PATIENT', 'DOCTOR', 'HOSPITAL_ADMIN', 'PLATFORM_ADMIN'],
      '/catalog': ['PATIENT'],
      '/appointments': ['PATIENT'],
      '/records': ['PATIENT'],
      '/orders': ['PATIENT'],
      '/doctor/appointments': ['DOCTOR'],
      '/doctor/records': ['DOCTOR'],
      '/hospital/departments': ['HOSPITAL_ADMIN'],
      '/hospital/doctors': ['HOSPITAL_ADMIN'],
      '/hospital/schedule': ['HOSPITAL_ADMIN'],
      '/hospital/statistics': ['HOSPITAL_ADMIN'],
      '/platform/tenants': ['PLATFORM_ADMIN'],
      '/platform/statistics': ['PLATFORM_ADMIN']
    })
    expect(router.getRoutes().some(route => route.path.includes(':module'))).toBe(false)
  })

  it('lazy loads every authenticated page component', () => {
    const protectedPages = router.getRoutes().filter(route => route.meta.roles)
    expect(protectedPages).toHaveLength(13)
    expect(protectedPages.every(route => typeof route.components?.default === 'function')).toBe(true)
  })

  it('restores a refresh session before the first protected navigation only once', async () => {
    setActivePinia(createPinia())
    sessionStorage.setItem(TOKEN_KEY, 'existing-token')
    const mock = new MockAdapter(http)
    mock.onGet('/api/me').reply(200, { data: {
      userId: 'patient-1', username: 'patient_demo', role: 'PATIENT', tenantId: null
    } })

    await router.push('/records')
    expect(router.currentRoute.value.path).toBe('/records')

    await router.push('/appointments')
    expect(mock.history.get.filter(request => request.url === '/api/me')).toHaveLength(1)
    mock.restore()
  })
})
