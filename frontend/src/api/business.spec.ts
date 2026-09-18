import { beforeEach, describe, expect, it } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { http, getApiErrorMessage } from './http'
import { searchScheduleSlots } from './catalog'
import { bookAppointment, listDoctorAppointments } from './appointments'
import { saveConsultationDraft } from './consultations'
import { payPatientOrder } from './orders'
import { createDoctor, publishScheduleSlot } from './administration'
import { uploadRecordFile } from './files'
import { getPlatformStatistics } from './statistics'

let mock: MockAdapter

beforeEach(() => {
  mock = new MockAdapter(http)
})

describe('business API contracts', () => {
  it('unwraps a paged catalog response and sends supported filters', async () => {
    mock.onGet('/api/catalog/schedule-slots').reply(config => {
      expect(config.params).toEqual({ tenantId: 'tenant-1', date: '2026-09-19', page: 1, size: 10 })
      return [200, { data: { content: [], totalElements: 0, totalPages: 0, size: 10, number: 1 } }]
    })

    await expect(searchScheduleSlots({ tenantId: 'tenant-1', date: '2026-09-19', page: 1, size: 10 }))
      .resolves.toEqual({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 1 })
  })

  it('uses role-specific appointment endpoints', async () => {
    mock.onPost('/api/patient/appointments', { slotId: 'slot-1' }).reply(200, { data: { id: 'appointment-1' } })
    mock.onGet('/api/doctor/appointments').reply(200, { data: [{ id: 'appointment-2' }] })

    await expect(bookAppointment('slot-1')).resolves.toMatchObject({ id: 'appointment-1' })
    await expect(listDoctorAppointments()).resolves.toEqual([{ id: 'appointment-2' }])
  })

  it('sends consultation drafts without adding tenant identity', async () => {
    const draft = {
      complaint: '头痛',
      diagnosis: '偏头痛',
      advice: '按时休息',
      prescriptionItems: [{ medicationName: '示例药物', dosage: '1片', frequency: '每日一次', durationDays: 3 }]
    }
    mock.onPut('/api/doctor/consultations/appointment-1/draft', draft).reply(200, { data: { id: 'record-1' } })

    await expect(saveConsultationDraft('appointment-1', draft)).resolves.toMatchObject({ id: 'record-1' })
  })

  it('maps order, administration, file, and statistics operations to backend endpoints', async () => {
    mock.onPost('/api/patient/orders/order-1/pay').reply(200, { data: { id: 'order-1', status: 'PAID' } })
    mock.onPost('/api/hospital/doctors').reply(200, { data: { id: 'doctor-1' } })
    mock.onPost('/api/hospital/schedule-slots').reply(200, { data: { id: 'slot-1' } })
    mock.onPost('/api/doctor/records/record-1/files').reply(config => {
      expect(config.data).toBeInstanceOf(FormData)
      return [200, { data: { id: 'file-1' } }]
    })
    mock.onGet('/api/platform/statistics').reply(config => {
      expect(config.params).toEqual({ from: '2026-09-01', to: '2026-09-30' })
      return [200, { data: { from: '2026-09-01', to: '2026-09-30', tenants: [] } }]
    })

    await expect(payPatientOrder('order-1')).resolves.toMatchObject({ status: 'PAID' })
    await expect(createDoctor({
      username: 'doctor_c', password: 'Demo@123456', departmentId: 'department-1',
      displayName: '王医生', title: '主治医师', biography: '演示简介'
    })).resolves.toMatchObject({ id: 'doctor-1' })
    await expect(publishScheduleSlot({
      doctorId: 'doctor-1', date: '2026-09-20', startTime: '09:00:00', endTime: '09:30:00', capacity: 5, fee: 20
    })).resolves.toMatchObject({ id: 'slot-1' })
    await expect(uploadRecordFile('record-1', new File(['demo'], 'report.pdf', { type: 'application/pdf' })))
      .resolves.toMatchObject({ id: 'file-1' })
    await expect(getPlatformStatistics('2026-09-01', '2026-09-30'))
      .resolves.toMatchObject({ tenants: [] })
  })

  it('extracts backend validation details through one error helper', () => {
    expect(getApiErrorMessage({
      response: { data: { code: 'VALIDATION_ERROR', message: '请求参数不合法', requestId: 'req-1', fieldErrors: { name: '不能为空' } } }
    })).toBe('请求参数不合法（name：不能为空）')
    expect(getApiErrorMessage(new Error('network down'))).toBe('network down')
  })
})
