import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import LoginView from './LoginView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

describe('login view', () => {
  beforeEach(() => { setActivePinia(createPinia()); sessionStorage.clear(); push.mockReset() })
  it('logs in and opens the dashboard', async () => {
    new MockAdapter(http).onPost('/api/auth/login').reply(200, { data: {
      accessToken: 'token', user: { userId: '1', username: 'patient_demo', role: 'PATIENT', tenantId: null }
    } })
    const wrapper = mount(LoginView)
    await wrapper.get('[data-test="username"]').setValue('patient_demo')
    await wrapper.get('[data-test="password"]').setValue('Demo@123456')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/dashboard'))
  })
})
