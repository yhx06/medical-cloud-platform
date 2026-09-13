import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { TOKEN_KEY } from '../api/http'
import type { AuthUser } from '../types/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser>()
  const isAuthenticated = computed(() => Boolean(user.value && sessionStorage.getItem(TOKEN_KEY)))
  const role = computed(() => user.value?.role)
  const tenantId = computed(() => user.value?.tenantId ?? null)

  async function login(username: string, password: string) {
    const result = await authApi.login(username, password)
    sessionStorage.setItem(TOKEN_KEY, result.accessToken)
    user.value = result.user
  }
  async function restore() {
    if (!sessionStorage.getItem(TOKEN_KEY)) return
    try { user.value = await authApi.currentUser() } catch { logout() }
  }
  function logout() { sessionStorage.removeItem(TOKEN_KEY); user.value = undefined }
  return { user, isAuthenticated, role, tenantId, login, restore, logout }
})
