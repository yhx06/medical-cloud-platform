import { createRouter, createWebHistory, type RouteMeta } from 'vue-router'
import type { UserRole } from '../types/auth'
import { useAuthStore } from '../stores/auth'

export interface AccessMeta { requiresAuth?: boolean; roles?: readonly UserRole[] }
export function canAccess(role: UserRole | undefined, meta: AccessMeta): true | string {
  if (meta.requiresAuth && !role) return '/login'
  if (meta.roles?.length && (!role || !meta.roles.includes(role))) return '/dashboard'
  return true
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/', component: () => import('../layouts/AppLayout.vue'), meta: { requiresAuth: true }, children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { roles: ['PATIENT', 'DOCTOR', 'HOSPITAL_ADMIN', 'PLATFORM_ADMIN'] }
      },
      { path: 'catalog', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PATIENT'] } },
      { path: 'appointments', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PATIENT'] } },
      { path: 'records', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PATIENT'] } },
      { path: 'orders', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PATIENT'] } },
      { path: 'doctor/appointments', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'doctor/records', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'hospital/departments', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['HOSPITAL_ADMIN'] } },
      { path: 'hospital/doctors', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['HOSPITAL_ADMIN'] } },
      { path: 'hospital/schedule', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['HOSPITAL_ADMIN'] } },
      { path: 'hospital/statistics', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['HOSPITAL_ADMIN'] } },
      { path: 'platform/tenants', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PLATFORM_ADMIN'] } },
      { path: 'platform/statistics', component: () => import('../views/ModulePlaceholderView.vue'), meta: { roles: ['PLATFORM_ADMIN'] } }
    ] }
  ]
})
router.beforeEach(to => canAccess(useAuthStore().role, to.meta as RouteMeta & AccessMeta))
