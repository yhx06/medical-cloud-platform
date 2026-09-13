# Medical Cloud Complete System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有基础框架扩展为可一键启动并完整演示“建院—排班—预约—接诊—病历—订单—支付—统计”的多租户医疗云课程系统。

**Architecture:** 保持 Vue 3 与 Spring Boot 模块化单体，按纵向业务切片同时交付数据库、领域行为、API、页面和测试。所有业务对象从服务端关系确定租户，MySQL 保证事务与并发约束，MinIO 保存私有附件。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring Security、JPA、Flyway、MySQL 8.4、MinIO、Vue 3.5、TypeScript 5.9、Pinia 3、Vue Router 4、Axios 1、Vitest 3、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-09-13-medical-cloud-complete-system-design.md`

## Global Constraints

- 根包保持 `com.medcloud.platform`，API 统一使用 `/api` 前缀。
- 角色固定为 `PATIENT`、`DOCTOR`、`HOSPITAL_ADMIN`、`PLATFORM_ADMIN`。
- 不信任请求头或请求体中的租户身份；租户必须从认证主体或服务端业务对象确定。
- 患者只访问本人数据；医生只处理本人且本租户预约；平台管理员不能读取病历、处方或附件。
- 金额使用 `BigDecimal` 与数据库 `DECIMAL(10,2)`，时间使用 `Instant` 或明确的本地日期和时间。
- 实际运行使用 MySQL，测试使用 H2 MySQL 兼容模式；并发预约另用 MySQL 容器冒烟验证。
- Bucket 私有，允许 PDF、JPG、PNG，单文件上限 10 MB，下载地址有效五分钟。
- 每个生产行为先写失败测试并观察 RED，再实现最小代码、观察 GREEN、重构并提交。
- 每个任务结束运行受影响测试；最终运行后端和前端全量验证。

---

### Task 1: 强化账号与租户生命周期

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__tenant_and_account_management.sql`
- Modify: `backend/src/main/java/com/medcloud/platform/tenant/domain/Tenant.java`
- Modify: `backend/src/main/java/com/medcloud/platform/identity/domain/UserAccount.java`
- Modify: `backend/src/main/java/com/medcloud/platform/identity/infrastructure/UserAccountRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/application/AccountAccessPolicy.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/application/PlatformTenantService.java`
- Modify: `backend/src/main/java/com/medcloud/platform/tenant/api/PlatformTenantController.java`
- Create: `backend/src/test/java/com/medcloud/platform/tenant/api/PlatformTenantManagementTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/security/DisabledAccountAccessTest.java`

**Interfaces:**
- Produces: `Tenant createTenant(CurrentUser actor, CreateTenantCommand command)`。
- Produces: `UserAccount createHospitalAdmin(CurrentUser actor, UUID tenantId, CreateAccountCommand command)`。
- Produces: `void requireActive(CurrentUser user)`，同时校验账号状态及租户状态。

- [ ] **Step 1: 写平台建院和停用账号的失败测试**

```java
mockMvc.perform(post("/api/platform/tenants").header(AUTHORIZATION, adminToken)
        .contentType(APPLICATION_JSON)
        .content("{\"name\":\"明德云医院\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.name").value("明德云医院"));

mockMvc.perform(get("/api/me").header(AUTHORIZATION, disabledToken))
    .andExpect(status().isForbidden())
    .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=PlatformTenantManagementTest,DisabledAccountAccessTest test`

Expected: FAIL because tenant mutation endpoints and active-account policy do not exist.

- [ ] **Step 3: 实现迁移、管理服务和请求级状态复核**

V3 adds `updated_at` to tenant and account plus indexes used by active lookups. Add `ACCOUNT_DISABLED` and `TENANT_DISABLED`; call `AccountAccessPolicy.requireActive` after JWT parsing and before protected controller execution. `POST /api/platform/tenants` creates an active UUID tenant; status endpoints accept only `ACTIVE` or `DISABLED`.

- [ ] **Step 4: 运行任务测试与既有认证测试**

Run: `mvn -f backend/pom.xml -Dtest=PlatformTenantManagementTest,DisabledAccountAccessTest,AuthControllerTest,RoleAuthorizationTest test`

Expected: PASS with zero failures.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: manage tenant and account lifecycle"
```

---

### Task 2: 实现科室与医生管理

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__department_and_doctor_profile.sql`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/domain/Department.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/domain/DoctorProfile.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/infrastructure/DepartmentRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/infrastructure/DoctorProfileRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/application/HospitalDirectoryService.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/api/HospitalDirectoryController.java`
- Create: `backend/src/test/java/com/medcloud/platform/tenant/api/HospitalDirectoryTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/tenant/api/HospitalDirectoryIsolationTest.java`

**Interfaces:**
- Produces: `DepartmentResponse createDepartment(CurrentUser actor, CreateDepartmentRequest request)`。
- Produces: `DoctorResponse createDoctor(CurrentUser actor, CreateDoctorRequest request)`。
- Produces: `/api/hospital/departments` and `/api/hospital/doctors` CRUD/status endpoints.

- [ ] **Step 1: 写科室唯一性、医生归属和跨租户失败测试**

```java
mockMvc.perform(post("/api/hospital/departments").header(AUTHORIZATION, hospitalAToken)
        .contentType(APPLICATION_JSON).content("{\"name\":\"内科\"}"))
    .andExpect(status().isOk());
mockMvc.perform(post("/api/hospital/departments").header(AUTHORIZATION, hospitalAToken)
        .contentType(APPLICATION_JSON).content("{\"name\":\"内科\"}"))
    .andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("DEPARTMENT_DUPLICATE"));
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=HospitalDirectoryTest,HospitalDirectoryIsolationTest test`

Expected: FAIL because department and doctor models are absent.

- [ ] **Step 3: 实现 V4、实体、服务与医院管理员接口**

V4 creates `department(id, tenant_id, name, description, status, created_at, updated_at)` with unique `(tenant_id,name)` and `doctor_profile(user_id, tenant_id, department_id, display_name, title, biography)`. Creation derives `tenant_id` from `CurrentUser`; doctor usernames remain globally unique and passwords use the production BCrypt encoder.

- [ ] **Step 4: 运行任务测试和租户隔离回归**

Run: `mvn -f backend/pom.xml -Dtest=HospitalDirectoryTest,HospitalDirectoryIsolationTest,TenantIsolationTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: manage hospital departments and doctors"
```

---

### Task 3: 实现排班号源与患者目录

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__schedule_slot.sql`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/domain/ScheduleSlot.java`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/domain/ScheduleSlotStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/infrastructure/ScheduleSlotRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/application/ScheduleService.java`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/api/HospitalScheduleController.java`
- Create: `backend/src/main/java/com/medcloud/platform/schedule/api/CatalogController.java`
- Create: `backend/src/test/java/com/medcloud/platform/schedule/api/HospitalScheduleTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/schedule/api/ScheduleCatalogTest.java`

**Interfaces:**
- Produces: `ScheduleSlotResponse publish(CurrentUser actor, PublishSlotRequest request)`。
- Produces: `Page<ScheduleSlotResponse> searchCatalog(UUID tenantId, UUID departmentId, LocalDate date, Pageable pageable)`。
- Produces: immutable slot fee, doctor, tenant, date/time and capacity values for appointment creation.

- [ ] **Step 1: 写号源校验和目录查询失败测试**

```java
mockMvc.perform(post("/api/hospital/schedule-slots").header(AUTHORIZATION, hospitalAToken)
        .contentType(APPLICATION_JSON).content(validSlotJson))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.capacity").value(2));
mockMvc.perform(get("/api/catalog/schedule-slots?tenantId=" + tenantA + "&date=2026-10-10")
        .header(AUTHORIZATION, patientToken))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.content[0].doctorName").exists());
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=HospitalScheduleTest,ScheduleCatalogTest test`

Expected: FAIL because schedule module is absent.

- [ ] **Step 3: 实现 V5、号源领域规则和双视角 API**

Reject past dates, non-positive capacity, negative fee, end time not after start time, disabled doctor or department, and overlapping active slots for one doctor. Catalog returns only active tenants, departments, doctors and published future slots; `remainingCapacity` is server-calculated.

- [ ] **Step 4: 运行任务测试**

Run: `mvn -f backend/pom.xml -Dtest=HospitalScheduleTest,ScheduleCatalogTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: publish and browse medical schedules"
```

---

### Task 4: 实现并发安全的患者预约

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__appointment.sql`
- Create: `backend/src/main/java/com/medcloud/platform/appointment/domain/Appointment.java`
- Modify: `backend/src/main/java/com/medcloud/platform/appointment/domain/AppointmentStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/appointment/infrastructure/AppointmentRepository.java`
- Modify: `backend/src/main/java/com/medcloud/platform/schedule/infrastructure/ScheduleSlotRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/appointment/application/AppointmentService.java`
- Create: `backend/src/main/java/com/medcloud/platform/appointment/api/PatientAppointmentController.java`
- Create: `backend/src/test/java/com/medcloud/platform/appointment/api/PatientAppointmentTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/appointment/application/AppointmentCapacityTest.java`

**Interfaces:**
- Produces: `AppointmentResponse book(CurrentUser patient, UUID slotId)` and `AppointmentResponse cancel(CurrentUser patient, UUID appointmentId)`。
- Produces: doctor and patient appointment queries that always filter by actor and tenant ownership.

- [ ] **Step 1: 写预约、重复预约、满号和取消失败测试**

```java
assertThat(service.book(patientOne, slotId).status()).isEqualTo(BOOKED);
assertThatThrownBy(() -> service.book(patientOne, slotId))
    .isInstanceOf(BusinessException.class)
    .extracting("code").isEqualTo(ErrorCode.APPOINTMENT_DUPLICATE);
assertThatThrownBy(() -> service.book(patientThree, fullSlotId))
    .isInstanceOf(BusinessException.class)
    .extracting("code").isEqualTo(ErrorCode.SCHEDULE_FULL);
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=PatientAppointmentTest,AppointmentCapacityTest test`

Expected: FAIL because appointment persistence and service are absent.

- [ ] **Step 3: 实现 V6 与悲观锁预约事务**

V6 stores tenant, patient, doctor, department, slot, date/time and fee snapshot. Add indexes for patient and doctor lists and a generated active-booking uniqueness strategy compatible with MySQL. `findByIdForUpdate` locks the slot; the service counts non-cancelled appointments before insert and maps constraint races to `APPOINTMENT_DUPLICATE` or `SCHEDULE_FULL`.

- [ ] **Step 4: 运行任务测试与状态机回归**

Run: `mvn -f backend/pom.xml -Dtest=PatientAppointmentTest,AppointmentCapacityTest,AppointmentStatusTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: book and cancel appointments safely"
```

---

### Task 5: 实现医生接诊、病历、处方与更正

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__consultation_and_prescription.sql`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/domain/Consultation.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/domain/ConsultationStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/domain/PrescriptionItem.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/domain/RecordCorrection.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/infrastructure/ConsultationRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/application/ConsultationService.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/api/DoctorConsultationController.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/api/PatientRecordController.java`
- Create: `backend/src/test/java/com/medcloud/platform/consultation/api/ConsultationWorkflowTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/consultation/domain/MedicalRecordImmutabilityTest.java`

**Interfaces:**
- Produces: `start`, `saveDraft`, `complete` and `appendCorrection` application methods.
- Produces: `ConsultationCompletedEvent(consultationId, appointmentId, patientId, tenantId, fee)` consumed synchronously by order creation.

- [ ] **Step 1: 写本人接诊、草稿、完成和不可覆盖测试**

```java
service.start(doctorA, appointmentA);
service.saveDraft(doctorA, appointmentA, new DraftCommand("头痛", "待确认", "观察", items));
MedicalRecordResponse completed = service.complete(doctorA, appointmentA);
assertThatThrownBy(() -> service.saveDraft(doctorA, appointmentA, changedDraft))
    .extracting("code").isEqualTo(ErrorCode.MEDICAL_RECORD_COMPLETED);
assertThatThrownBy(() -> service.start(doctorB, appointmentA))
    .extracting("code").isEqualTo(ErrorCode.AUTH_FORBIDDEN);
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=ConsultationWorkflowTest,MedicalRecordImmutabilityTest test`

Expected: FAIL because consultation workflow is absent.

- [ ] **Step 3: 实现 V7、病历聚合与医生患者接口**

Completion requires non-blank complaint, diagnosis and advice, freezes consultation content, transitions appointment to `COMPLETED`, and emits the immutable completion value inside the same application transaction. Corrections append rows and never update original medical text. Patient record queries require matching patient ID.

- [ ] **Step 4: 运行任务测试与预约回归**

Run: `mvn -f backend/pom.xml -Dtest=ConsultationWorkflowTest,MedicalRecordImmutabilityTest,PatientAppointmentTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: complete consultations and medical records"
```

---

### Task 6: 实现订单与幂等模拟支付

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__medical_order.sql`
- Create: `backend/src/main/java/com/medcloud/platform/order/domain/MedicalOrder.java`
- Create: `backend/src/main/java/com/medcloud/platform/order/infrastructure/MedicalOrderRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/order/application/MedicalOrderService.java`
- Create: `backend/src/main/java/com/medcloud/platform/order/api/PatientOrderController.java`
- Create: `backend/src/test/java/com/medcloud/platform/order/application/OrderCreationTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/order/api/PatientPaymentTest.java`

**Interfaces:**
- Consumes: `ConsultationCompletedEvent` from Task 5.
- Produces: `MedicalOrderResponse createForCompletedConsultation(...)` and `MedicalOrderResponse pay(CurrentUser patient, UUID orderId)`。

- [ ] **Step 1: 写金额快照、所有权和支付幂等失败测试**

```java
MedicalOrderResponse order = service.createForCompletedConsultation(eventWithFee("39.00"));
assertThat(order.amount()).isEqualByComparingTo("39.00");
MedicalOrderResponse first = service.pay(patient, order.id());
MedicalOrderResponse second = service.pay(patient, order.id());
assertThat(second.transactionNo()).isEqualTo(first.transactionNo());
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=OrderCreationTest,PatientPaymentTest test`

Expected: FAIL because order persistence and payment behavior are absent.

- [ ] **Step 3: 实现 V8、同步订单生成与幂等支付**

Use a unique consultation foreign key and unique transaction number. Completion and order insert share one transaction. `pay` locks the order; `PAID` returns the existing result, `PENDING` records `paidAt` and transaction number, and another patient's request returns 403.

- [ ] **Step 4: 运行任务测试和状态机回归**

Run: `mvn -f backend/pom.xml -Dtest=OrderCreationTest,PatientPaymentTest,OrderStatusTest,ConsultationWorkflowTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: generate and pay medical orders"
```

---

### Task 7: 实现 MinIO 私有附件

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Create: `backend/src/main/resources/db/migration/V9__file_metadata.sql`
- Create: `backend/src/main/java/com/medcloud/platform/file/domain/FileMetadata.java`
- Create: `backend/src/main/java/com/medcloud/platform/file/infrastructure/FileMetadataRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/file/infrastructure/MinioFileAccessAdapter.java`
- Create: `backend/src/main/java/com/medcloud/platform/file/application/MedicalFileService.java`
- Create: `backend/src/main/java/com/medcloud/platform/file/api/MedicalFileController.java`
- Create: `backend/src/test/java/com/medcloud/platform/file/application/MedicalFileValidationTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/file/api/MedicalFileAuthorizationTest.java`

**Interfaces:**
- Implements: `FileAccessPort.store(...)` and `FileAccessPort.load(...)`; extend the port with `delete(String objectKey)` and `presignGet(String objectKey, Duration ttl)`.
- Produces: `FileResponse upload(CurrentUser doctor, UUID recordId, MultipartFile file)` and `DownloadUrlResponse createDownloadUrl(CurrentUser user, UUID fileId)`。

- [ ] **Step 1: 写类型、大小、授权和五分钟地址失败测试**

```java
assertThatThrownBy(() -> service.upload(doctor, recordId, executableFile))
    .extracting("code").isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED);
assertThatThrownBy(() -> service.upload(doctor, recordId, fileOver10Mb))
    .extracting("code").isEqualTo(ErrorCode.FILE_TOO_LARGE);
verify(port).presignGet(objectKey, Duration.ofMinutes(5));
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=MedicalFileValidationTest,MedicalFileAuthorizationTest test`

Expected: FAIL because file metadata and service are absent.

- [ ] **Step 3: 实现 V9、MinIO adapter 与补偿删除**

Pin the MinIO Java SDK. Validate declared media type and filename suffix, enforce 10 MB before upload, generate non-guessable object keys under the tenant UUID, persist metadata after upload, and delete the object if metadata persistence throws. Authorization supports the responsible doctor, the patient owner and same-tenant hospital admin metadata listing; platform admin is denied.

- [ ] **Step 4: 运行任务测试**

Run: `mvn -f backend/pom.xml -Dtest=MedicalFileValidationTest,MedicalFileAuthorizationTest test`

Expected: PASS without a live MinIO server because the application service tests use a fake port.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: secure medical record attachments"
```

---

### Task 8: 实现审计与统计

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__audit_event.sql`
- Create: `backend/src/main/java/com/medcloud/platform/audit/domain/AuditEvent.java`
- Create: `backend/src/main/java/com/medcloud/platform/audit/application/AuditService.java`
- Create: `backend/src/main/java/com/medcloud/platform/audit/infrastructure/AuditEventRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/statistics/infrastructure/StatisticsRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/statistics/application/StatisticsService.java`
- Create: `backend/src/main/java/com/medcloud/platform/statistics/api/StatisticsController.java`
- Create: `backend/src/test/java/com/medcloud/platform/audit/AuditPrivacyTest.java`
- Create: `backend/src/test/java/com/medcloud/platform/statistics/StatisticsAuthorizationTest.java`

**Interfaces:**
- Produces: `void record(CurrentUser actor, AuditAction action, String objectType, UUID objectId, AuditResult result, String requestId)`。
- Produces: `HospitalStatisticsResponse hospital(CurrentUser admin, LocalDate from, LocalDate to)` and `PlatformStatisticsResponse platform(CurrentUser admin, LocalDate from, LocalDate to)`。

- [ ] **Step 1: 写统计口径、租户过滤与审计隐私失败测试**

```java
assertThat(service.hospital(hospitalA, from, to).paidRevenue()).isEqualByComparingTo("78.00");
assertThat(service.hospital(hospitalA, from, to).tenantId()).isEqualTo(tenantA);
assertThat(objectMapper.writeValueAsString(savedAudit)).doesNotContain("诊断", "Bearer", "Demo@123456");
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=AuditPrivacyTest,StatisticsAuthorizationTest test`

Expected: FAIL because audit and aggregate queries are absent.

- [ ] **Step 3: 实现 V10、显式审计调用和聚合查询**

Record only enumerated actions and identifiers at successful or rejected mutation boundaries. Hospital SQL always has tenant predicate; platform SQL groups by tenant and returns counts and paid totals only. Date range is inclusive and limited to 366 days.

- [ ] **Step 4: 运行任务测试**

Run: `mvn -f backend/pom.xml -Dtest=AuditPrivacyTest,StatisticsAuthorizationTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend
git commit -m "feat: add privacy-safe audit and statistics"
```

---

### Task 9: 建立前端业务 API、路由和共享组件

**Files:**
- Create: `frontend/src/api/catalog.ts`
- Create: `frontend/src/api/appointments.ts`
- Create: `frontend/src/api/consultations.ts`
- Create: `frontend/src/api/orders.ts`
- Create: `frontend/src/api/administration.ts`
- Create: `frontend/src/api/files.ts`
- Create: `frontend/src/api/statistics.ts`
- Create: `frontend/src/types/business.ts`
- Create: `frontend/src/components/StatusTag.vue`
- Create: `frontend/src/components/AsyncState.vue`
- Create: `frontend/src/components/PaginationBar.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/navigation/roleMenus.ts`
- Modify: `frontend/src/navigation/roleMenus.spec.ts`
- Modify: `frontend/src/router/router.spec.ts`

**Interfaces:**
- Consumes: all Task 1–8 JSON API contracts.
- Produces: typed API functions and role-specific lazy-loaded routes; no component calls Axios directly.

- [ ] **Step 1: 写完整角色菜单和路由矩阵失败测试**

```ts
expect(menusFor('PATIENT').map(x => x.key)).toEqual(['dashboard','catalog','appointments','records','orders'])
expect(menusFor('DOCTOR').map(x => x.key)).toEqual(['dashboard','doctor-appointments','doctor-records'])
expect(canAccess('PLATFORM_ADMIN', patientRecordMeta)).toBe('/dashboard')
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm --prefix frontend test -- --run src/navigation/roleMenus.spec.ts src/router/router.spec.ts`

Expected: FAIL because complete routes and menus are not registered.

- [ ] **Step 3: 实现类型化 API、共享显示状态和角色路由**

Use one `PageResponse<T>` type, central error extraction, route meta role arrays and lazy page imports. Replace wildcard module placeholder routing with explicit paths. `AsyncState` renders loading, empty and error states without hiding the page heading.

- [ ] **Step 4: 运行前端基础测试和类型检查**

Run: `npm --prefix frontend test -- --run src/navigation/roleMenus.spec.ts src/router/router.spec.ts && npm --prefix frontend run build`

Expected: PASS and production build succeeds.

- [ ] **Step 5: 提交**

```powershell
git add frontend
git commit -m "feat: define complete frontend navigation and APIs"
```

---

### Task 10: 实现四角色完整业务页面

**Files:**
- Create: `frontend/src/views/patient/CatalogView.vue`
- Create: `frontend/src/views/patient/AppointmentsView.vue`
- Create: `frontend/src/views/patient/RecordsView.vue`
- Create: `frontend/src/views/patient/OrdersView.vue`
- Create: `frontend/src/views/doctor/DoctorAppointmentsView.vue`
- Create: `frontend/src/views/doctor/ConsultationEditorView.vue`
- Create: `frontend/src/views/doctor/DoctorRecordsView.vue`
- Create: `frontend/src/views/hospital/DepartmentsView.vue`
- Create: `frontend/src/views/hospital/DoctorsView.vue`
- Create: `frontend/src/views/hospital/ScheduleSlotsView.vue`
- Create: `frontend/src/views/hospital/HospitalStatisticsView.vue`
- Create: `frontend/src/views/platform/TenantsView.vue`
- Create: `frontend/src/views/platform/PlatformStatisticsView.vue`
- Modify: `frontend/src/views/DashboardView.vue`
- Create: `frontend/src/views/patient/PatientWorkflow.spec.ts`
- Create: `frontend/src/views/doctor/ConsultationEditorView.spec.ts`
- Create: `frontend/src/views/hospital/HospitalAdministration.spec.ts`
- Create: `frontend/src/views/platform/PlatformAdministration.spec.ts`

**Interfaces:**
- Consumes: typed API modules from Task 9.
- Produces: every page named in the approved design, with form validation, confirmation and operation feedback.

- [ ] **Step 1: 写患者预约与支付页面失败测试**

```ts
await wrapper.get('[data-test="book-slot"]').trigger('click')
expect(appointmentsApi.book).toHaveBeenCalledWith('slot-a')
await ordersWrapper.get('[data-test="pay-order"]').trigger('click')
expect(ordersApi.pay).toHaveBeenCalledWith('order-a')
```

- [ ] **Step 2: 写医生、医院和平台管理页面失败测试**

```ts
await editor.get('[data-test="complete-consultation"]').trigger('click')
expect(consultationsApi.complete).toHaveBeenCalledWith('appointment-a', expectedForm)
expect(hospitalPage.text()).toContain('科室管理')
expect(platformPage.text()).not.toContain('诊断内容')
```

- [ ] **Step 3: 运行页面测试确认 RED**

Run: `npm --prefix frontend test -- --run src/views`

Expected: FAIL because business pages do not exist.

- [ ] **Step 4: 实现页面及真实交互**

Use accessible labels, disabled submit states, explicit destructive confirmations, server-side pagination and stable status labels. Dashboard metrics come from APIs, not hard-coded values. Upload accepts only the approved file types and displays the 10 MB limit before selection.

- [ ] **Step 5: 运行前端全量测试和构建**

Run: `npm --prefix frontend test -- --run && npm --prefix frontend run build`

Expected: all Vitest suites pass and Vite build exits 0.

- [ ] **Step 6: 提交**

```powershell
git add frontend
git commit -m "feat: implement role-based medical workflows"
```

---

### Task 11: 实现全栈容器化与可重复演示数据

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/env.example`
- Create: `backend/src/main/resources/db/migration/V11__complete_demo_workflow.sql`
- Create: `backend/src/main/java/com/medcloud/platform/common/demo/DemoDataInitializer.java`
- Create: `backend/src/test/java/com/medcloud/platform/common/demo/DemoDataInitializerTest.java`
- Modify: `docs/development.md`
- Create: `docs/demo-accounts.md`

**Interfaces:**
- Produces: `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up --build -d` starts four services.
- Produces: frontend `/api` reverse proxy to backend and health-dependent startup.

- [ ] **Step 1: 写容器配置验收脚本说明并确认当前 RED**

Run: `docker compose -f deploy/docker-compose.yml --env-file deploy/env.example config --services`

Expected before implementation: output contains only `mysql` and `minio`, so the four-service requirement fails.

- [ ] **Step 2: 添加多阶段镜像、Nginx 代理和 Compose 服务**

Backend image builds the jar with Maven and runs on Java 21. Frontend image builds static assets and serves them from Nginx; `/api` and `/actuator` proxy to `backend:8080`. Backend waits on MySQL health; frontend waits on backend health. Secrets come only from `deploy/.env`.

- [ ] **Step 3: 添加完整虚构演示数据**

V11 seeds two departments, doctor profiles and stable course accounts; keep BCrypt only in SQL. A `dev`-profile `DemoDataInitializer` idempotently creates published slots relative to the current date so the demo cannot expire. `DemoDataInitializerTest` runs initialization twice and asserts that slot counts do not increase on the second run. Document every username, role, tenant and intended demo action.

- [ ] **Step 4: 验证 Compose 与服务健康**

```powershell
Copy-Item deploy/env.example deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up --build -d
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
```

Expected: `mysql`, `minio`, `backend` and `frontend` are running; health-enabled services report healthy; `http://localhost` serves the login page and `http://localhost/actuator/health` returns `UP`.

- [ ] **Step 5: 提交**

```powershell
git add backend frontend deploy docs
git commit -m "build: run the complete platform with compose"
```

---

### Task 12: 全链路验收、隐私检查与交付文档

**Files:**
- Modify: `README.md`
- Modify: `docs/smoke-test.md`
- Create: `docs/api-overview.md`
- Create: `docs/final-verification.md`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: repeatable course demonstration and final verification evidence.

- [ ] **Step 1: 运行后端全量测试**

Run: `mvn -f backend/pom.xml test`

Expected: all JUnit tests pass with zero failures and zero errors.

- [ ] **Step 2: 运行前端全量测试和构建**

```powershell
npm --prefix frontend test -- --run
npm --prefix frontend run build
```

Expected: all Vitest tests pass and TypeScript/Vite production build succeeds.

- [ ] **Step 3: 执行完整角色冒烟流程**

From a fresh Compose database: platform admin creates a tenant and hospital admin; hospital admin creates department, doctor and slot; patient books; assigned doctor starts and completes consultation with prescription; patient reads record, obtains an authorized attachment URL and pays; hospital and platform statistics increase according to their scopes.

- [ ] **Step 4: 执行越权与隐私检查**

```powershell
rg -n "password|Bearer |diagnosis|complaint" backend/src/main/java -g "*.java"
rg -n "Demo@123456|JWT_SECRET|MINIO_SECRET_KEY" -g "!**/target/**" -g "!**/node_modules/**" -g "!**/.superpowers/**"
```

Expected: no logger serializes credentials or medical text; demo password appears only in migrations, tests and documentation; secret variable names appear only in configuration and documentation; no real secret value is committed.

- [ ] **Step 5: 更新交付文档并记录验证结果**

README gives one-command startup, four role entry points and project boundaries. `api-overview.md` lists endpoints and role requirements. `final-verification.md` records exact commands, date, test totals, build result, Compose services and remaining non-blocking dependency warnings.

- [ ] **Step 6: 提交并推送功能分支**

```powershell
git add README.md docs
git commit -m "docs: finalize medical cloud course delivery"
git push origin feature/complete-system
```

Expected: remote branch contains every reviewed slice and the working tree is clean.
