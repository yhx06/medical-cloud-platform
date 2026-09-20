import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import { http } from '../api/http'
import LoginView from './LoginView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
let mock: MockAdapter

describe('login view', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    push.mockReset()
    mock = new MockAdapter(http)
  })
  afterEach(() => mock.restore())

  it('logs in and opens the dashboard', async () => {
    mock.onPost('/api/auth/login').reply(200, { data: {
      accessToken: 'token', user: { userId: '1', username: 'patient_demo', role: 'PATIENT', tenantId: null }
    } })
    const wrapper = mount(LoginView)
    await wrapper.get('[data-test="username"]').setValue('patient_demo')
    await wrapper.get('[data-test="password"]').setValue('Demo@123456')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/dashboard'))
  })

  it('shows backend field errors through the shared error format', async () => {
    mock.onPost('/api/auth/login').reply(400, {
      code: 'VALIDATION_ERROR', message: '请求参数不合法', requestId: 'request-1',
      fieldErrors: { username: '不能为空' }
    })
    const wrapper = mount(LoginView)

    await wrapper.get('form').trigger('submit')

    await vi.waitFor(() => expect(wrapper.get('[role="alert"]').text())
      .toBe('请求参数不合法（username：不能为空）'))
  })

  it('shows a general backend error message', async () => {
    mock.onPost('/api/auth/login').reply(500, {
      code: 'INTERNAL_ERROR', message: '系统繁忙', requestId: 'request-2', fieldErrors: {}
    })
    const wrapper = mount(LoginView)

    await wrapper.get('form').trigger('submit')

    await vi.waitFor(() => expect(wrapper.get('[role="alert"]').text()).toBe('系统繁忙'))
  })
})
