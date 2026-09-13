import { createRouter, createWebHistory, type RouteMeta } from 'vue-router'
import type { UserRole } from '../types/auth'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import AppLayout from '../layouts/AppLayout.vue'
import DashboardView from '../views/DashboardView.vue'
import ModulePlaceholderView from '../views/ModulePlaceholderView.vue'

export interface AccessMeta { requiresAuth?: boolean; roles?: UserRole[] }
export function canAccess(role: UserRole | undefined, meta: AccessMeta): true | string {
  if (meta.requiresAuth && !role) return '/login'
  if (meta.roles?.length && (!role || !meta.roles.includes(role))) return '/dashboard'
  return true
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: AppLayout, meta: { requiresAuth: true }, children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: DashboardView },
      { path: ':module', component: ModulePlaceholderView }
    ] }
  ]
})
router.beforeEach(to => canAccess(useAuthStore().role, to.meta as RouteMeta & AccessMeta))
