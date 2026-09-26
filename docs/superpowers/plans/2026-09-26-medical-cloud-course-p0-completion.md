# 医疗云课程版 P0 补全 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有课程版双容器链路上补齐需求说明书的 P0 缺口，并保留现有演示数据和已通过的业务回归。

**Architecture:** 保持 `contract` → Tomcat 9 `portal` → WildFly 10 `paas`/EJB → JPA/JNDI → MySQL 的现有边界。每个任务以契约、服务、页面和测试组成一个可验收的纵向切片；增量迁移先于依赖新列的业务发布。

**Tech Stack:** Java 8、Maven、Servlet/JSP、JAX-RS、EJB、JPA、MySQL、JUnit 5、PowerShell、PowerDesigner 16.5。

**Spec:** `docs/superpowers/specs/2026-09-26-medical-cloud-course-p0-completion-design.md`

## Global Constraints

- 只改 `course/` 课程版及其部署/架构文档，不改 `backend/`、`frontend/`；不增加新框架或真实医疗、支付接入。
- Java 源/目标均为 1.8；课堂主链为 Tomcat 9、WildFly 10、JNDI/JPA、MySQL。
- P0 范围为 `FR-AUTH-03`、`FR-TEN-02/03/04`、`FR-APT-01/03/04`、`FR-CON-01/02/04`、`FR-ORD-02/03`；P1/P2 暂缓。
- 机构由数据库中的当前账号确定，不信任浏览器传入的机构 ID；私有资源跨租户或非本人访问一律拒绝。
- 预约、取消及接诊竞争须先锁号源后锁预约；有效占位为非 `CANCELED` 预约。病历、订单按预约唯一；支付重复调用幂等。
- 应用使用 `Asia/Shanghai` 判断业务日期；旧 `PAID` 行的 `paid_at` 允许为空并显示“历史时间未知”。
- 所有数据、账号、病历、支付都是虚构课堂演示；不得清除隔离库中的既有数据、覆盖教师安装或提交密钥。

## Review Focus

以下五类容易漏掉的输入，须在所属任务的失败测试中钉住，而不是只靠人工审查：

1. `Alice`/`alice` 注册冲突：现有 MySQL 不区分大小写排序规则下只能保留一个账号（Task 2）。
2. 同机构的医生关联到其他机构科室：创建和修改均拒绝，不能只检查科室存在（Task 3）。
3. 目录筛选中的畸形日期或不匹配的医生/科室：返回 `400` 或空集，不退化成全量号源（Task 4）。
4. 取消、接诊开始、最后一个名额预约并发：不会超卖、重复有效预约或产生已取消但在接诊的记录（Task 5）。
5. 历史 `PAID` 行没有支付时间与新订单重复支付：旧行仍可显示，新订单的首次 `paid_at` 不变（Task 7）。

## 文件职责图

- `course/contract/src/main/java/com/medcloud/course/contract/`：新增注册与医院维护 DTO，扩展号源、预约、病历、订单视图；只放可序列化的跨容器契约。
- `course/paas/src/main/java/com/medcloud/course/paas/auth/`：注册和账号校验；`tenant/`、`schedule/`：科室/医生/号源管理及目录；`appointment/`：预约容量、取消和历史；`consultation/`：接诊及病历；`order/`：支付及医院订单。
- `course/paas/src/main/java/com/medcloud/course/paas/api/`：JAX-RS 路由、请求转换和错误映射，不持有租户授权逻辑。
- `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java` 与 `WEB-INF/views/`：角色页面、表单、CSRF 和结果展示；`PaasClient` 仅扩展必需的 HTTP 方法。
- `course/deploy/schema.sql`、`course/deploy/migrations/`：新库建表与保留数据的旧库升级；`docs/course-architecture/medical-cloud-course.pdm`：与实际表一致的可编辑模型。
- `course/*/src/test/`、`course/deploy/tests/`：相应切片的红绿测试；不把跨容器端到端测试冒充单元测试。

---

### Task 1: 保留数据的数据库升级

**Files:** Modify `course/deploy/schema.sql`, `course/paas/src/main/java/com/medcloud/course/paas/appointment/Appointment.java`, `course/paas/src/main/java/com/medcloud/course/paas/consultation/ClinicalRecord.java`, `course/paas/src/main/java/com/medcloud/course/paas/order/PaymentOrder.java`; create `course/deploy/migrations/001_p0_completion.sql`, `course/deploy/scripts/upgrade-p0-database.ps1`, `course/deploy/tests/upgrade-p0-database.Tests.ps1`.

**Interfaces:** Produce nullable `appointment.started_at`, `clinical_record.complaint/notes/completed_at`, `payment_order.paid_at`; replace `uk_course_patient_slot` with a non-unique `(patient_id, slot_id)` index. New database schema and migrated old schema must agree.

- [ ] **Step 1: Write failing migration tests.** Fixture with one `BOOKED`、one `COMPLETED` and an old `PAID` row; assert all rows and IDs remain, new columns exist, old `paid_at IS NULL`, old patient-slot unique constraint is gone and non-unique index exists. Wrong schema/instance and partial prior migration must stop before mutation.
- [ ] **Step 2: Run `Invoke-Pester course/deploy/tests/upgrade-p0-database.Tests.ps1`; confirm the new assertions fail** against the current schema/tooling, without touching the user's existing course database.
- [ ] **Step 3: Implement the schema, entity mappings and guarded migration.** `upgrade-p0-database.ps1` must require explicit MySQL executable, host/port/database and backup destination; verify `medical_cloud_course` plus expected pre-state before DDL, create a backup, then apply `001_p0_completion.sql`. Do not use `CREATE TABLE IF NOT EXISTS` as migration or silently delete rows.
- [ ] **Step 4: Run the same Pester test and `mvn -f course/pom.xml clean verify`; expect zero failures.** Check migrated fixture counts and fresh schema structure match.
- [ ] **Step 5: Commit** only migration, schema, entity mappings and their tests as `feat(course): add preserving P0 database migration`.

### Task 2: 患者自助注册

**Files:** Create `course/contract/src/main/java/com/medcloud/course/contract/RegisterRequest.java`, `RegisterResponse.java`; modify `course/paas/src/main/java/com/medcloud/course/paas/auth/AuthService.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/AuthResource.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/webapp/WEB-INF/views/login.jsp`; create `course/portal/src/main/webapp/WEB-INF/views/register.jsp`; test `course/paas/src/test/java/com/medcloud/course/paas/RegistrationTest.java` and `course/portal/src/test/java/com/medcloud/course/portal/RegistrationFlowTest.java`.

**Interfaces:** `AuthService.register(RegisterRequest): RegisterResponse`; `POST /auth/register` accepts only `username`, `password`; response never contains password/hash. Username `3–64` ASCII `[A-Za-z0-9_]`, password `8–72` UTF-8 bytes; server assigns `PATIENT`/`ACTIVE`/null tenant.

- [ ] **Step 1: Write failing tests** for valid register→login, role/tenant/status injection ignored, invalid username/password, `Alice` then `alice` conflict, concurrent duplicate username, and portal password confirmation plus no password echo.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm the new tests fail** for missing registration behavior.
- [ ] **Step 3: Implement DTO, service, endpoint and portal flow.** Map duplicate username to `409` from a controlled domain conflict, including database unique race; generate UUID and BCrypt hash. Register form must use UTF-8 and the existing CSRF/session presentation conventions without requiring login.
- [ ] **Step 4: Run the same Maven command; expect zero failures**, then check a fresh registered patient receives no management role.
- [ ] **Step 5: Commit** this vertical slice as `feat(course): add patient self-registration`.

### Task 3: 医院科室与医生维护

**Files:** Modify `course/paas/src/main/java/com/medcloud/course/paas/tenant/Department.java`, `course/paas/src/main/java/com/medcloud/course/paas/auth/Account.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PaasClient.java`, `course/portal/src/main/webapp/WEB-INF/views/hospital.jsp`; create focused `HospitalDepartmentService.java`, `HospitalDoctorService.java`, `DoctorProfile.java` under `paas/tenant/`, `HospitalResource.java` under `paas/api/`, `DepartmentRequest.java`, `DoctorRequest.java`, `DepartmentView.java`, `DoctorView.java`, `DoctorCreateResponse.java` under `contract/`; test `HospitalResourceTest.java` under `course/paas/src/test/java/com/medcloud/course/paas/` and `HospitalPortalTest.java` under `course/portal/src/test/java/com/medcloud/course/portal/`.

**Interfaces:** `HospitalDepartmentService.list(CurrentActor): List<Department>`、`create(CurrentActor, String name): Department`、`update(CurrentActor, String id, String name, String status): Department`; `HospitalDoctorService.list(CurrentActor): List<DoctorView>`、`create(CurrentActor, String username, String displayName, String departmentId): DoctorCreateResponse`、`update(CurrentActor, String userId, String displayName, String departmentId, String status): DoctorView`. These back `GET/POST/PUT /hospital/departments` and `/hospital/doctors`; status is `ACTIVE` or `DISABLED`. `PaasClient.put(String path, String token, Object request): JsonNode` sends the same JSON/error envelope as `post`. Request DTOs carry names, status and department ID only; tenant ID is never writable. Created doctor response includes a generated initial password once; list/update responses omit it.

- [ ] **Step 1: Write failing tests** for own-tenant create/list/edit, cross-tenant ID denial, doctor→foreign-department denial on create and update, global username collision, invalid disabled department, referenced resource logical stop, pending-appointment refusal to disable doctor/department, and portal `PUT` with CSRF and UTF-8 JSON.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm these tests fail** at the new service/route behavior.
- [ ] **Step 3: Implement services, endpoint and hospital forms.** Re-read active hospital admin account/tenant, persist doctor account and profile in one transaction, issue random temporary password once, hash with BCrypt; never return it in list/log. Modify `PortalServlet` only for routes it owns and keep CSRF and output escaping.
- [ ] **Step 4: Run the same Maven command; expect zero failures**, including old doctor/patient permissions.
- [ ] **Step 5: Commit** as `feat(course): let hospitals manage departments and doctors`.

### Task 4: 排班发布和可用号源筛选

**Files:** Modify `course/paas/src/main/java/com/medcloud/course/paas/schedule/ScheduleSlot.java`, `DirectoryService.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/CatalogResource.java`, `course/contract/src/main/java/com/medcloud/course/contract/SlotView.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/webapp/WEB-INF/views/hospital.jsp`, `patient.jsp`; create `SlotAdminService.java`, `SlotAvailability.java` under `paas/schedule/`, `HospitalSlotResource.java` under `paas/api/`, `SlotRequest.java` under `contract/`; test `HospitalSlotTest.java`, `SlotSearchTest.java` under `course/paas/src/test/java/com/medcloud/course/paas/` and `SlotSearchPortalTest.java` under portal tests.

**Interfaces:** `SlotAdminService.list(CurrentActor): List<ScheduleSlot>`、`create(CurrentActor, SlotRequest): ScheduleSlot`、`update(CurrentActor, String slotId, SlotRequest): ScheduleSlot` back `GET/POST/PUT /hospital/slots`; `SlotRequest` carries department/doctor/date/start/end/capacity/fee/status, never tenant. Create status is `PUBLISHED`; PUT status may be `PUBLISHED` or `WITHDRAWN`. `DirectoryService.availableSlots(String tenantId, String departmentId, String doctorId, Date date, Date minimumDate): List<SlotAvailability>` returns slot plus `int remaining`; keep the existing two-argument overload for old callers. `GET /catalog/slots` keeps `tenantId` required and adds optional `departmentId/doctorId/date`; `SlotView` adds `remaining`.

- [ ] **Step 1: Write failing tests** for publishing in own active department with own active doctor, cross-tenant/disabled resource rejection, no-change-after-valid-booking rule, withdrawal, no capacity-zero or past slot, combined filter, full slot exclusion, malformed date `400`, mismatched doctor/department empty result, and no fallback to unfiltered list.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm the new tests fail.**
- [ ] **Step 3: Implement admin service, filter and pages.** Remaining capacity comes from count of non-`CANCELED` appointments, not a second mutable column; booking must still recheck under lock. Keep existing `GET /catalog/slots?tenantId=...` clients valid.
- [ ] **Step 4: Run the same Maven command; expect zero failures**, including existing catalog and booking tests.
- [ ] **Step 5: Commit** as `feat(course): manage and filter available slots`.

### Task 5: 预约取消、重约和患者历史

**Files:** Modify `course/paas/src/main/java/com/medcloud/course/paas/appointment/Appointment.java`, `BookingService.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/AppointmentResource.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/webapp/WEB-INF/views/patient.jsp`, `doctor.jsp`; create `course/contract/src/main/java/com/medcloud/course/contract/AppointmentHistoryView.java`; test `course/paas/src/test/java/com/medcloud/course/paas/BookingIsolationTest.java`, new `CancellationTest.java`, `course/portal/src/test/java/com/medcloud/course/portal/PortalFlowTest.java`.

**Interfaces:** `BookingService.forPatient(CurrentActor): List<AppointmentHistoryView>`; `BookingService.cancel(CurrentActor, String appointmentId, java.time.Instant now): Appointment`; `Appointment.cancel(): void`. New routes `GET /patient/appointments`, `POST /patient/appointments/{id}/cancel`; `AppointmentHistoryView` contains appointment ID/status/booked date and linked hospital/department/doctor/slot date/time, while old `AppointmentView` stays compatible.

- [ ] **Step 1: Write failing tests** for own history including canceled rows, other-patient denial, cancel before start, cancel at/after start conflict, repeated cancel conflict, same patient rebook with new ID, effective capacity after cancel, and concurrent booking/cancel/start leaving at most one effective booking and no canceled-in-progress row.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm the new tests fail.**
- [ ] **Step 3: Implement state and lock order.** Lock slot first, then relevant appointment; count/query effective rows with current/pessimistic read. `ClinicalService.start` must follow the same slot→appointment order. Use explicit `Asia/Shanghai` for slot cutoff, preserve canceled history, and keep appointment status transitions one-way.
- [ ] **Step 4: Run the same Maven command and the last-slot concurrency fixture; expect zero failures and no overbooking.**
- [ ] **Step 5: Commit** as `feat(course): support cancellation and patient history`.

### Task 6: 接诊字段与医生病历查询

**Files:** Modify `course/paas/src/main/java/com/medcloud/course/paas/consultation/ClinicalService.java`, `ClinicalRecord.java`, `course/paas/src/main/java/com/medcloud/course/paas/appointment/Appointment.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/ClinicalResource.java`, `course/contract/src/main/java/com/medcloud/course/contract/CompleteConsultationRequest.java`, `ClinicalRecordView.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/webapp/WEB-INF/views/doctor.jsp`; test `ClinicalPaymentFlowTest.java`, new `DoctorRecordTest.java` under paas tests and `PortalFlowTest.java` under portal tests.

**Interfaces:** `Appointment.start(java.time.Instant now)` records `started_at`; `ClinicalService.start(CurrentActor, String appointmentId, java.time.Instant now): Appointment`; `ClinicalService.complete(CurrentActor, String appointmentId, String complaint, String diagnosis, String advice, String notes, java.time.Instant now): ClinicalRecord`; `ClinicalService.forDoctor(CurrentActor, Date from, Date to): List<ClinicalRecord>` backs `GET /doctor/records`. Persist Instants as nullable `java.sql.Timestamp` in these JPA 2.1 entities. `CompleteConsultationRequest` extends existing JSON by optional `complaint` and `notes`; new portal requires complaint, legacy callers may omit it.

- [ ] **Step 1: Write failing tests** for server-assigned start/completion timestamps independent of JVM default timezone, UTF-8 complaint/advice/notes round trip, old request without complaint, blank diagnosis/advice rejection, duplicate completion creates no second record/order, doctor sees only own records and date filter, and cross-tenant record denial.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm the new tests fail.**
- [ ] **Step 3: Implement new fields, DTOs, doctor list route and portal form/list.** Keep the existing patient record path and the single transaction for completion plus order creation; nullable legacy columns display safely.
- [ ] **Step 4: Run the same Maven command; expect zero failures**, including the original end-to-end clinical/payment unit flow.
- [ ] **Step 5: Commit** as `feat(course): complete consultation records`.

### Task 7: 支付时间与医院订单查询

**Files:** Modify `course/paas/src/main/java/com/medcloud/course/paas/order/PaymentOrder.java`, `PaymentService.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/PaymentResource.java`, `course/contract/src/main/java/com/medcloud/course/contract/PaymentOrderView.java`, `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `course/portal/src/main/webapp/WEB-INF/views/hospital.jsp`, `patient.jsp`; test `ClinicalPaymentFlowTest.java`, new `HospitalOrderTest.java` under paas tests and `HospitalPortalTest.java` under portal tests.

**Interfaces:** `PaymentOrder.pay(java.time.Instant now): void` sets nullable `java.sql.Timestamp paid_at` only on first transition; `PaymentService.forHospital(CurrentActor, String status, Date from, Date to): List<PaymentOrder>` backs `GET /hospital/orders`; old `GET /patient/appointments/{id}/order` and pay route remain.

- [ ] **Step 1: Write failing tests** for first pay timestamp, second pay retaining same timestamp, old `PAID` with null timestamp displayed as “历史时间未知”, only same-tenant hospital orders returned, status/date filters and invalid filter rejection, and hospital admin cannot invoke patient pay.
- [ ] **Step 2: Run `mvn -f course/pom.xml -pl paas,portal -am test`; confirm the new tests fail.**
- [ ] **Step 3: Implement idempotent timestamp and hospital list/query/view.** Use order row write lock; never backfill a guessed time for old paid rows. Limit list size with stable order.
- [ ] **Step 4: Run the same Maven command; expect zero failures**, including existing patient payment tests.
- [ ] **Step 5: Commit** as `feat(course): show payment time and hospital orders`.

### Task 8: 模型、部署说明和真实链路验收

**Files:** Modify `docs/course-architecture/medical-cloud-course.pdm`, `docs/course-architecture/medical-cloud-er.png`, `docs/course-architecture/README.md`, `course/deploy/README.md`, `course/deploy/scripts/e2e-demo.ps1`, `course/deploy/scripts/smoke-test.ps1`; create `course/deploy/tests/p0-e2e.Tests.ps1` if the existing scripts cannot express the full scenario safely.

**Interfaces:** A new, isolated P0 demonstration path registers a fictional patient and creates a future fictional slot; it does not reset or erase existing records. The `.pdm` and exported ER diagram show the same final tables, keys, indexes and columns as `schema.sql`.

- [ ] **Step 1: Write failing deployment/contract checks** for model/schema parity, new interface paths, registration→hospital publish→filter→book→cancel→rebook→start→complete→pay→hospital order, and cross-tenant denial; tests must use a dedicated fixture/IDs so repeated runs do not alter unrelated rows.
- [ ] **Step 2: Run the checks and `mvn -f course/pom.xml clean verify`; record the missing paths/model mismatch** before final integration.
- [ ] **Step 3: Update PowerDesigner source/export and interface/deployment docs, then extend demo scripts.** Verify the exported diagram visually and document safe migration, backup, course timezone, fictional-data notice, and report screenshot checklist.
- [ ] **Step 4: Run `mvn -f course/pom.xml clean verify`, Pester checks, and the full script on isolated JDK 8/Tomcat 9/WildFly 10/MySQL copies; expect zero failures.** Compare existing row counts before/after migration (only new scenario rows may be added), inspect both container logs, and record exact tool versions and any unverified teacher-specific versions.
- [ ] **Step 5: Commit** verified model/docs/scripts as `docs(course): verify full P0 course workflow`.

## Completion gate

After Task 8, run a whole-branch review against the spec, check `git diff --check` and a clean `mvn -f course/pom.xml clean verify`, plus real-container smoke/e2e in the isolated runtime. Report failures and version gaps explicitly; do not claim teacher's precise installation is verified unless it was actually run. Ask before merging or pushing if the chosen execution workflow does not already authorize that action.
