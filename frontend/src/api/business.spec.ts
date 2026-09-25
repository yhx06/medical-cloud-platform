import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import type { AxiosRequestConfig } from 'axios'
import { http, getApiErrorMessage } from './http'
import { listCatalogDepartments, listCatalogTenants, searchScheduleSlots } from './catalog'
import {
  bookAppointment, cancelAppointment, getPatientAppointment,
  listDoctorAppointments, listPatientAppointments
} from './appointments'
import {
  appendRecordCorrection, completeConsultation, getDoctorRecord, getPatientRecord, listDoctorRecords, listPatientRecords,
  saveConsultationDraft, startConsultation
} from './consultations'
import { getPatientOrder, listPatientOrders, payPatientOrder } from './orders'
import {
  changeAccountStatus, changeDoctorStatus, changeTenantStatus, createDepartment,
  changeScheduleSlotStatus, createDoctor, createHospitalAdmin, createTenant, getHospitalProfile,
  listDepartments, listDoctors, listHospitalAdministrators, listHospitalScheduleSlots, listTenants,
  publishScheduleSlot, updateDepartment
} from './administration'
import { createFileDownloadUrl, listRecordFiles, uploadRecordFile } from './files'
import { getHospitalStatistics, getPlatformStatistics } from './statistics'
import type { UserRole } from '../types/auth'
import type { ActiveStatus } from '../types/business'

let mock: MockAdapter

beforeEach(() => {
  mock = new MockAdapter(http)
})

interface ApiContract {
  name: string
  method: string
  url: string
  params?: Record<string, unknown>
  body?: unknown
  invoke: () => Promise<unknown>
}

function normalizeBody(config: AxiosRequestConfig): unknown {
  if (config.data instanceof FormData) {
    const file = config.data.get('file')
    return file instanceof File ? { file: { name: file.name, type: file.type, size: file.size } } : { file }
  }
  if (typeof config.data === 'string') return JSON.parse(config.data)
  return config.data
}

function businessApiContracts(): ApiContract[] {
  const doctor = {
    username: 'doctor_c', password: 'Demo@123456', departmentId: 'department-1',
    displayName: '王医生', title: '主治医师', biography: '演示简介'
  }
  const slot = {
    doctorId: 'doctor-1', date: '2026-09-20', startTime: '09:00:00',
    endTime: '09:30:00', capacity: 5, fee: 20
  }
  const draft = {
    complaint: '头痛', diagnosis: '偏头痛', advice: '按时休息',
    prescriptionItems: [{ medicationName: '示例药物', dosage: '1片', frequency: '每日一次', durationDays: 3 }]
  }
  const file = new File(['demo'], 'report.pdf', { type: 'application/pdf' })

  return [
    { name: 'listCatalogTenants', method: 'GET', url: '/api/catalog/tenants', invoke: listCatalogTenants },
    { name: 'listCatalogDepartments', method: 'GET', url: '/api/catalog/tenants/tenant-1/departments', invoke: () => listCatalogDepartments('tenant-1') },
    {
      name: 'searchScheduleSlots', method: 'GET', url: '/api/catalog/schedule-slots',
      params: { tenantId: 'tenant-1', departmentId: 'department-1', date: '2026-09-19', page: 1, size: 10, sort: 'date,asc' },
      invoke: () => searchScheduleSlots({
        tenantId: 'tenant-1', departmentId: 'department-1', date: '2026-09-19', page: 1, size: 10, sort: 'date,asc'
      })
    },
    { name: 'bookAppointment', method: 'POST', url: '/api/patient/appointments', body: { slotId: 'slot-1' }, invoke: () => bookAppointment('slot-1') },
    { name: 'listPatientAppointments', method: 'GET', url: '/api/patient/appointments', invoke: listPatientAppointments },
    { name: 'getPatientAppointment', method: 'GET', url: '/api/patient/appointments/appointment-1', invoke: () => getPatientAppointment('appointment-1') },
    { name: 'cancelAppointment', method: 'POST', url: '/api/patient/appointments/appointment-1/cancel', invoke: () => cancelAppointment('appointment-1') },
    { name: 'listDoctorAppointments', method: 'GET', url: '/api/doctor/appointments', invoke: listDoctorAppointments },
    { name: 'listPatientRecords', method: 'GET', url: '/api/patient/records', invoke: listPatientRecords },
    { name: 'getPatientRecord', method: 'GET', url: '/api/patient/records/record-1', invoke: () => getPatientRecord('record-1') },
    { name: 'listDoctorRecords', method: 'GET', url: '/api/doctor/records', invoke: listDoctorRecords },
    { name: 'getDoctorRecord', method: 'GET', url: '/api/doctor/records/record-1', invoke: () => getDoctorRecord('record-1') },
    { name: 'startConsultation', method: 'POST', url: '/api/doctor/appointments/appointment-1/start', invoke: () => startConsultation('appointment-1') },
    { name: 'saveConsultationDraft', method: 'PUT', url: '/api/doctor/consultations/appointment-1/draft', body: draft, invoke: () => saveConsultationDraft('appointment-1', draft) },
    { name: 'completeConsultation', method: 'POST', url: '/api/doctor/consultations/appointment-1/complete', invoke: () => completeConsultation('appointment-1') },
    { name: 'appendRecordCorrection', method: 'POST', url: '/api/doctor/records/record-1/corrections', body: { reason: '补充说明' }, invoke: () => appendRecordCorrection('record-1', '补充说明') },
    { name: 'listPatientOrders', method: 'GET', url: '/api/patient/orders', invoke: listPatientOrders },
    { name: 'getPatientOrder', method: 'GET', url: '/api/patient/orders/order-1', invoke: () => getPatientOrder('order-1') },
    { name: 'payPatientOrder', method: 'POST', url: '/api/patient/orders/order-1/pay', invoke: () => payPatientOrder('order-1') },
    { name: 'listTenants', method: 'GET', url: '/api/platform/tenants', invoke: listTenants },
    { name: 'createTenant', method: 'POST', url: '/api/platform/tenants', body: { name: '示例医院' }, invoke: () => createTenant('示例医院') },
    {
      name: 'createHospitalAdmin', method: 'POST', url: '/api/platform/tenants/tenant-1/admins',
      body: { username: 'hospital_admin_c', password: 'Demo@123456' },
      invoke: () => createHospitalAdmin('tenant-1', 'hospital_admin_c', 'Demo@123456')
    },
    { name: 'changeTenantStatus', method: 'PATCH', url: '/api/platform/tenants/tenant-1/status', body: { status: 'DISABLED' }, invoke: () => changeTenantStatus('tenant-1', 'DISABLED') },
    { name: 'changeAccountStatus', method: 'PATCH', url: '/api/platform/accounts/account-1/status', body: { status: 'DISABLED' }, invoke: () => changeAccountStatus('account-1', 'DISABLED') },
    { name: 'getHospitalProfile', method: 'GET', url: '/api/hospital/profile', invoke: getHospitalProfile },
    { name: 'listDepartments', method: 'GET', url: '/api/hospital/departments', invoke: listDepartments },
    { name: 'createDepartment', method: 'POST', url: '/api/hospital/departments', body: { name: '内科', description: '演示科室' }, invoke: () => createDepartment({ name: '内科', description: '演示科室' }) },
    {
      name: 'updateDepartment', method: 'PUT', url: '/api/hospital/departments/department-1',
      body: { name: '心内科', description: '更新简介', status: 'ACTIVE' },
      invoke: () => updateDepartment('department-1', { name: '心内科', description: '更新简介', status: 'ACTIVE' })
    },
    { name: 'listDoctors', method: 'GET', url: '/api/hospital/doctors', invoke: listDoctors },
    { name: 'createDoctor', method: 'POST', url: '/api/hospital/doctors', body: doctor, invoke: () => createDoctor(doctor) },
    { name: 'changeDoctorStatus', method: 'PUT', url: '/api/hospital/doctors/doctor-1/status', body: { status: 'DISABLED' }, invoke: () => changeDoctorStatus('doctor-1', 'DISABLED') },
    { name: 'publishScheduleSlot', method: 'POST', url: '/api/hospital/schedule-slots', body: slot, invoke: () => publishScheduleSlot(slot) },
    { name: 'listHospitalScheduleSlots', method: 'GET', url: '/api/hospital/schedule-slots', invoke: listHospitalScheduleSlots },
    { name: 'changeScheduleSlotStatus', method: 'PUT', url: '/api/hospital/schedule-slots/slot-1', body: { status: 'DISABLED' }, invoke: () => changeScheduleSlotStatus('slot-1', 'DISABLED') },
    { name: 'listHospitalAdministrators', method: 'GET', url: '/api/platform/tenants/tenant-1/administrators', invoke: () => listHospitalAdministrators('tenant-1') },
    {
      name: 'uploadRecordFile', method: 'POST', url: '/api/doctor/records/record-1/files',
      body: { file: { name: 'report.pdf', type: 'application/pdf', size: 4 } },
      invoke: () => uploadRecordFile('record-1', file)
    },
    { name: 'listRecordFiles', method: 'GET', url: '/api/records/record-1/files', invoke: () => listRecordFiles('record-1') },
    { name: 'createFileDownloadUrl', method: 'POST', url: '/api/files/file-1/download-url', invoke: () => createFileDownloadUrl('file-1') },
    {
      name: 'getHospitalStatistics', method: 'GET', url: '/api/hospital/statistics',
      params: { from: '2026-09-01', to: '2026-09-30' },
      invoke: () => getHospitalStatistics('2026-09-01', '2026-09-30')
    },
    {
      name: 'getPlatformStatistics', method: 'GET', url: '/api/platform/statistics',
      params: { from: '2026-09-01', to: '2026-09-30' },
      invoke: () => getPlatformStatistics('2026-09-01', '2026-09-30')
    }
  ]
}

describe('business API contracts', () => {
  it.each(businessApiContracts())('$name sends the exact backend request and unwraps data', async contract => {
    const responseData = { contract: contract.name }
    mock.onAny().reply(config => {
      expect(config.method?.toUpperCase()).toBe(contract.method)
      expect(config.url).toBe(contract.url)
      expect(config.params).toEqual(contract.params)
      expect(normalizeBody(config)).toEqual(contract.body)
      return [200, { data: responseData, requestId: 'request-1' }]
    })

    await expect(contract.invoke()).resolves.toEqual(responseData)
  })

  it('returns the backend account contract for any user role and nullable tenant', async () => {
    mock.onPatch('/api/platform/accounts/account-1/status', { status: 'DISABLED' }).reply(200, { data: {
      id: 'account-1', username: 'platform_admin', role: 'PLATFORM_ADMIN', status: 'DISABLED', tenantId: null
    } })

    const account = await changeAccountStatus('account-1', 'DISABLED')

    expect(account).toEqual({
      id: 'account-1', username: 'platform_admin', role: 'PLATFORM_ADMIN', status: 'DISABLED', tenantId: null
    })
    expectTypeOf(account).toEqualTypeOf<{
      id: string
      username: string
      role: UserRole
      status: ActiveStatus
      tenantId: string | null
    }>()
  })

  it('accepts empty and partially null consultation drafts exactly as the backend does', async () => {
    mock.onPut('/api/doctor/consultations/appointment-empty/draft', {}).reply(200, { data: { id: 'record-empty' } })
    mock.onPut('/api/doctor/consultations/appointment-partial/draft', {
      complaint: null, prescriptionItems: null
    }).reply(200, { data: { id: 'record-partial' } })

    await expect(saveConsultationDraft('appointment-empty', {})).resolves.toEqual({ id: 'record-empty' })
    await expect(saveConsultationDraft('appointment-partial', {
      complaint: null, prescriptionItems: null
    })).resolves.toEqual({ id: 'record-partial' })
  })

  it('extracts backend validation details through one error helper', () => {
    expect(getApiErrorMessage({
      response: { data: { code: 'VALIDATION_ERROR', message: '请求参数不合法', requestId: 'req-1', fieldErrors: { name: '不能为空' } } }
    })).toBe('请求参数不合法（name：不能为空）')
    expect(getApiErrorMessage(new Error('network down'))).toBe('network down')
  })
})
