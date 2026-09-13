import type { UserRole } from '../types/auth'

export interface MenuItem { key: string; label: string; path: string }
const dashboard: MenuItem = { key: 'dashboard', label: '工作台', path: '/dashboard' }
const menus: Record<UserRole, MenuItem[]> = {
  PATIENT: [dashboard, { key: 'appointments', label: '我的预约', path: '/appointments' }, { key: 'records-orders', label: '诊疗记录与订单', path: '/records-orders' }],
  DOCTOR: [dashboard, { key: 'schedule', label: '排班与预约', path: '/schedule' }, { key: 'consultations', label: '在线接诊', path: '/consultations' }],
  HOSPITAL_ADMIN: [dashboard, { key: 'hospital', label: '医院管理', path: '/hospital' }, { key: 'hospital-statistics', label: '院内统计', path: '/hospital-statistics' }],
  PLATFORM_ADMIN: [dashboard, { key: 'tenants', label: '租户管理', path: '/tenants' }, { key: 'platform-statistics', label: '平台统计', path: '/platform-statistics' }]
}
export const menusFor = (role: UserRole): MenuItem[] => menus[role]
