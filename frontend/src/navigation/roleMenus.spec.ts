import { describe, expect, it } from 'vitest'
import { menusFor } from './roleMenus'

describe('role menus', () => {
  it('provides the complete patient workflow', () => {
    expect(menusFor('PATIENT')).toEqual([
      { key: 'dashboard', label: '工作台', path: '/dashboard' },
      { key: 'catalog', label: '预约挂号', path: '/catalog' },
      { key: 'appointments', label: '我的预约', path: '/appointments' },
      { key: 'records', label: '我的病历', path: '/records' },
      { key: 'orders', label: '我的订单', path: '/orders' }
    ])
  })

  it('provides the complete doctor workflow', () => {
    expect(menusFor('DOCTOR')).toEqual([
      { key: 'dashboard', label: '工作台', path: '/dashboard' },
      { key: 'doctor-appointments', label: '我的预约', path: '/doctor/appointments' },
      { key: 'doctor-records', label: '接诊记录', path: '/doctor/records' }
    ])
  })

  it('provides hospital administration without platform operations', () => {
    expect(menusFor('HOSPITAL_ADMIN')).toEqual([
      { key: 'dashboard', label: '工作台', path: '/dashboard' },
      { key: 'departments', label: '科室管理', path: '/hospital/departments' },
      { key: 'doctors', label: '医生管理', path: '/hospital/doctors' },
      { key: 'schedule', label: '排班号源', path: '/hospital/schedule' },
      { key: 'hospital-statistics', label: '机构统计', path: '/hospital/statistics' }
    ])
  })

  it('provides platform operations without patient records or files', () => {
    expect(menusFor('PLATFORM_ADMIN')).toEqual([
      { key: 'dashboard', label: '工作台', path: '/dashboard' },
      { key: 'tenants', label: '租户管理', path: '/platform/tenants' },
      { key: 'platform-statistics', label: '平台统计', path: '/platform/statistics' }
    ])
  })
})
