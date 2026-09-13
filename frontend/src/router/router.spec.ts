import { describe, expect, it } from 'vitest'
import { canAccess } from './index'

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
})
