import type { UserRole } from '../types/auth'

export interface MenuItem { key: string; label: string; path: string }
const dashboard: MenuItem = { key: 'dashboard', label: '工作台', path: '/dashboard' }
const menus: Record<UserRole, MenuItem[]> = {
  PATIENT: [
    dashboard,
    { key: 'catalog', label: '预约挂号', path: '/catalog' },
    { key: 'appointments', label: '我的预约', path: '/appointments' },
    { key: 'records', label: '我的病历', path: '/records' },
    { key: 'orders', label: '我的订单', path: '/orders' }
  ],
  DOCTOR: [
    dashboard,
    { key: 'doctor-appointments', label: '我的预约', path: '/doctor/appointments' },
    { key: 'doctor-records', label: '接诊记录', path: '/doctor/records' }
  ],
  HOSPITAL_ADMIN: [
    dashboard,
    { key: 'departments', label: '科室管理', path: '/hospital/departments' },
    { key: 'doctors', label: '医生管理', path: '/hospital/doctors' },
    { key: 'schedule', label: '排班号源', path: '/hospital/schedule' },
    { key: 'hospital-statistics', label: '机构统计', path: '/hospital/statistics' }
  ],
  PLATFORM_ADMIN: [
    dashboard,
    { key: 'tenants', label: '租户管理', path: '/platform/tenants' },
    { key: 'platform-statistics', label: '平台统计', path: '/platform/statistics' }
  ]
}
export const menusFor = (role: UserRole): MenuItem[] => menus[role]
