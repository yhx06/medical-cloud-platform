import { describe, expect, it } from 'vitest'
import { menusFor } from './roleMenus'

describe('role menus', () => {
  it('provides only patient modules', () => {
    expect(menusFor('PATIENT').map(item => item.key)).toEqual(['dashboard', 'appointments', 'records-orders'])
  })
  it('provides platform operations modules', () => {
    expect(menusFor('PLATFORM_ADMIN').map(item => item.key)).toEqual(['dashboard', 'tenants', 'platform-statistics'])
  })
})
