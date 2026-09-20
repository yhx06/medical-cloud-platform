import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusTag from './StatusTag.vue'
import AsyncState from './AsyncState.vue'
import PaginationBar from './PaginationBar.vue'

describe('StatusTag', () => {
  it('presents known backend states with a localized label and stable state hook', () => {
    const wrapper = mount(StatusTag, { props: { status: 'IN_PROGRESS' } })
    expect(wrapper.text()).toBe('接诊中')
    expect(wrapper.attributes('data-status')).toBe('IN_PROGRESS')
  })

  it('keeps an unknown server state visible', () => {
    expect(mount(StatusTag, { props: { status: 'REVIEWING' } }).text()).toBe('REVIEWING')
  })
})

describe('AsyncState', () => {
  it('shows the active async state and restores content when ready', async () => {
    const wrapper = mount(AsyncState, {
      props: { loading: true, empty: false, error: null },
      slots: { heading: '<h1>页面标题</h1>', default: '<p>业务内容</p>' }
    })
    expect(wrapper.get('h1').text()).toBe('页面标题')
    expect(wrapper.get('[role="status"]').text()).toContain('加载中')

    await wrapper.setProps({ loading: false, error: '加载失败' })
    expect(wrapper.get('h1').text()).toBe('页面标题')
    expect(wrapper.get('[role="alert"]').text()).toBe('加载失败')

    await wrapper.setProps({ error: null, empty: true })
    expect(wrapper.get('h1').text()).toBe('页面标题')
    expect(wrapper.get('[data-test="empty-state"]').text()).toContain('暂无数据')

    await wrapper.setProps({ empty: false })
    expect(wrapper.get('h1').text()).toBe('页面标题')
    expect(wrapper.text()).toContain('业务内容')
  })
})

describe('PaginationBar', () => {
  it('emits zero-based page changes and disables boundary controls', async () => {
    const wrapper = mount(PaginationBar, { props: { page: 0, size: 10, totalElements: 21 } })
    expect(wrapper.get('[data-test="previous-page"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('第 1 / 3 页')

    await wrapper.get('[data-test="next-page"]').trigger('click')
    expect(wrapper.emitted('change')).toEqual([[1]])

    await wrapper.setProps({ page: 2 })
    expect(wrapper.get('[data-test="next-page"]').attributes('disabled')).toBeDefined()
  })
})
