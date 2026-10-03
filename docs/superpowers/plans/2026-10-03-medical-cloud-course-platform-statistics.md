# 医疗云课程版医院启停与统计看板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在课程版实现医院启停、停用后的授权历史只读，以及医院/平台安全统计和可演示的 JSP 看板。

**Architecture:** 在现有 contract、paas、portal 中纵向扩展。EJB/JPA 在事务内保护医院业务状态并执行聚合，JAX-RS 输出安全 DTO，Tomcat Servlet/JSP 负责会话、CSRF、表单和展示；不引入另一套服务或数据库。

**Tech Stack:** Java 8、Java EE 7、Tomcat 9、WildFly 10、JPA 2.1/Hibernate 5.0、JNDI、MySQL；现有 JUnit 5、H2 1.4.200、嵌入式 Tomcat、Node 内置测试、Pester。

**Spec:** `docs/superpowers/specs/2026-10-03-medical-cloud-course-platform-statistics-design.md`（用户于设计提交后回复“继续”，本计划按书面设计获确认处理）。

## Global Constraints

- 只修改 `course/` 及相关说明，不迁回原 `backend/` Java 21/Spring 或 `frontend/` Vue 技术栈。
- IaaS 的 70 台服务器仍仅为 PowerDesigner 逻辑模型，不实际启动。
- 不包括新建医院、创建医院管理员、平台停用账号、附件/对象存储、持久化审计、真实支付、退款、真实诊疗、AI、统计导出或监控大屏。
- 医院启停不修改任何 `Account.status`。
- 取消和支付虽关联旧业务，也属于写操作，停用期间一并冻结。
- 新统计接口使用 `from`、`to`，严格接受真实有效的 `yyyy-MM-dd` 日期，首尾日期均包含。
- 两者同时缺省时为上海当天及前 29 天；只提供一个日期视为 400；区间超过 366 个自然日为 400。
- 本轮无需数据库迁移，保持 `hibernate.hbm2ddl.auto=validate`；不执行初始化脚本或修补旧时间字段。
- 金额使用 `BigDecimal` 和数据库 `DECIMAL`，以人民币元显示两位小数，不用浮点数累加。
- 统计在同一 EJB/JTA 事务、经确认的 MySQL `REPEATABLE READ` 一致读快照中执行。
- 本轮不得暂存无关 `Medical Cloud API Probe.eam`，不切换或合并旧 E: 工作区，不自动 push。

## Review Focus

1. 停用发生在已登录或已读到 ACTIVE 之后：不能凭旧令牌、一级缓存或旧页面提交写入（Task 1、6）。
2. 重新启用医院时科室/医生已独立停用：医院启用不能越过资源状态，过期预约也不能恢复取消资格（Task 1、2）。
3. 一个页面同时筛选订单和统计、随后启停或重置：两组日期不能互相污染，错误不能变成全量查询（Task 4）。
4. 366 天全零趋势、金额含分、医院名含 HTML：不除零、不出现 NaN、不丢精度、不执行注入文本（Task 3、5）。
5. 同页部分调用成功后另一调用返回 401：不能展示先前成功获取的私有数据或保留成功提示（Task 4）。

## 执行约定与文件结构

实际工作目录为 `C:/Users/yhx/.codex/worktrees/course-environment/云服务项目-医疗云`，分支 `codex/course-environment`。执行前依 using-git-worktrees 检查并复用此隔离工作树，不创建另一工作树，不在 E: 旧检出实施。基线为 `c7b1a60` 加本计划提交；无关 `.eam` 保留原状。

以下文件路径均相对该工作目录。保持现有无参 EJB 构造和 `Service(EntityManager)` 测试构造；新增服务同样提供两者。`TenantAccessPolicy` 是显式接收 EntityManager 的事务内辅助类，不自行开启/提交事务。DTO 遵循现有 Java 8 安全视图风格，旧构造签名保留，用重载扩展字段。

顺序为 Task 1 → 2 → 3 → 4 → 5 → 6；不并行修改共享服务/Servlet。每项先 RED 后 GREEN，再精确暂存该项文件、检查暂存 diff、提交和评审。RED 必须由对应行为断言触发；若首轮仅因新增类型缺失而无法编译，补最小接口骨架后再证明行为失败。不把计划文档中测试示例直接当完整夹具：每个测试文件用现有 RESOURCE_LOCAL 持久化单元创建隔离 H2 库、准备两医院/四角色及所需资源，显式提交/回滚；禁止复用运行库做 create-drop。

### Task 1: 原子地分离认证、历史读与全部业务写保护

**Files:**

- Create: `course/paas/src/main/java/com/medcloud/course/paas/tenant/TenantAccessPolicy.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/auth/AuthService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/tenant/HospitalDepartmentService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/tenant/HospitalDoctorService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/schedule/SlotAdminService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/appointment/BookingService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/consultation/ClinicalService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/order/PaymentService.java`
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/api/ApiFailureMapper.java`
- Test: `course/paas/src/test/java/com/medcloud/course/paas/TenantAccessPolicyTest.java`, `TenantReadOnlyTest.java`, `TenantWriteFreezeTest.java`（后两者同目录）
- Modify tests: `course/paas/src/test/java/com/medcloud/course/paas/BookingIsolationTest.java`, `HospitalOrderTest.java`

**Interfaces:**

- Produces: `TenantAccessPolicy(EntityManager)`；`Account requireAccount(CurrentActor actor, Role requiredRole)`；`Tenant readableTenant(CurrentActor actor, Role requiredRole)`；`Tenant readableTenant(String tenantId)`；`Tenant lockWritableTenant(String tenantId)`。
- `requireAccount` 从数据库账号判定真实角色/归属；缺主体/停用账号为 SecurityException，错误角色为 AccessDeniedException。传入 actor 的 tenantId 不能覆盖数据库归属。
- `readableTenant` 只接受存在且状态 ACTIVE/DISABLED 的医院，不取业务锁；`lockWritableTenant` 取医院 PESSIMISTIC_READ 并 refresh，非 ACTIVE 为 IllegalStateException。
- Preserve: 现有业务 public 方法签名不变；`HospitalDepartmentService.tenant(CurrentActor)` 改为本院历史读资格，不取医院锁。create/update 独立取得写资格，避免旧 `MySqlSlotSnapshotTest` 的普通读快照步骤变成阻塞锁。

- [ ] **Step 1: 写身份/历史与写冻结测试。** 将旧 `disabledTenantRejectsDoctorAndHospitalLoginAndExistingTokensAcrossTransactions` 改名为允许认证但限制写入的测试，保留独立账号停用测试不变。先在独立事务停用医院，再用原 EntityManager/旧 token 验证：

```java
assertEquals(Role.DOCTOR, auth.authenticate(doctorToken).getRole());
assertDoesNotThrow(() -> auth.login(doctorLogin));
assertDoesNotThrow(() -> departments.list(hospitalActor));
assertThrows(IllegalStateException.class, () -> bookings.book(patientActor, slotId, today));
assertThrows(IllegalStateException.class, () -> clinical.complete(doctorActor, progressId, "虚构诊断", "演示建议"));
assertThrows(IllegalStateException.class, () -> payments.pay(patientActor, unpaidOrderId));
```

  用单独事务逐项覆盖取消、开始接诊、重复支付、科室/医生/号源 create/update；拒绝后 rollback/clear，断言预约、病历、账单行数/状态/时间/金额未改变。验证医院列表/订单、医生预约/病历、患者历史/病历/账单均保持原权限；医院不存在、状态未知、独立停用账号、伪造角色和跨院对象继续拒绝。重新启用后过期取消仍失败，独立停用医生仍不能接诊。
- [ ] **Step 2: 运行 RED。** `mvn -f course/pom.xml -pl paas -am '-Dtest=TenantAccessPolicyTest,TenantReadOnlyTest,TenantWriteFreezeTest,BookingIsolationTest,HospitalOrderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；预期新历史登录/写冻结断言失败，记录真实失败原因。
- [ ] **Step 3: 实现策略并在同一提交接入所有服务。** 先验证本人/本院对象，再锁医院，后锁原业务链；取业务锁后重新核对医院/资源归属。读方法不得复用写策略。Auth 使用已知医院状态但不要求 ACTIVE。医院账户管理保留原资源停用限制；临床完成与账单创建保持同事务。ApiFailureMapper 将 JPA 锁超时/悲观锁冲突映射 409，保持 401/403/400/500 语义；EJB 包装下仍正确回滚，不吞掉异常后返回成功。
- [ ] **Step 4: 运行 GREEN 与完整 PaaS 回归。** 重跑 Step 2，再运行 `mvn -f course/pom.xml -pl paas -am test`；预期 Failures/Errors 均 0。检查每个写入口医院锁在现有下游锁前、没有保护遗漏；不在本项发布仅修改认证的中间 WAR。
- [ ] **Step 5: 检查精确文件 diff 后提交。** `git diff --check`，仅暂存 Files 中改动；`git diff --cached --check`，提交 `feat(course): preserve hospital history and freeze disabled tenant writes`。

### Task 2: 平台医院启停、角色上下文和患者写状态契约

**Files:**

- Create: `course/contract/src/main/java/com/medcloud/course/contract/TenantView.java`, `TenantPage.java`, `TenantStatusRequest.java`, `TenantContextView.java`（后三者同目录）
- Create: `course/paas/src/main/java/com/medcloud/course/paas/tenant/PlatformTenantService.java`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/api/PlatformTenantResource.java`, `TenantContextResource.java`（后者同目录）
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/tenant/Tenant.java`
- Modify: `course/contract/src/main/java/com/medcloud/course/contract/AppointmentHistoryView.java`, `PaymentOrderView.java`（同目录）
- Modify: `course/paas/src/main/java/com/medcloud/course/paas/appointment/BookingService.java`, `course/paas/src/main/java/com/medcloud/course/paas/order/PaymentService.java`, `course/paas/src/main/java/com/medcloud/course/paas/api/PaymentResource.java`
- Test: `course/paas/src/test/java/com/medcloud/course/paas/PlatformTenantTest.java`, `TenantContextApiTest.java`, `TenantViewPrivacyTest.java`（后两者同目录）

**Interfaces:**

- Consumes: Task 1 的账号验证、医院只读策略和医院写锁顺序。
- Produces: `Tenant.setStatus(String status)` 只接受 ACTIVE/DISABLED；`PlatformTenantService.list(CurrentActor,int page,int size) -> TenantPage`；`changeStatus(CurrentActor,String id,TenantStatusRequest) -> TenantView`。
- `TenantView(id,name,status)`；`TenantPage(items,total,page,size)`，total 为 long，page/size 为 int；`TenantStatusRequest` 无参及 `(String status,String expectedStatus)` 构造、对应 getter/setter，拒绝其他 JSON 更新字段；`TenantContextView(id,name,status,businessWritable)`。
- API：GET `/platform/tenants`、PUT `/platform/tenants/{id}/status`；GET `/hospital/context` 和 `/doctor/context`。Resource 保留无参和显式注入的测试构造。
- `AppointmentHistoryView` 新增 `tenantStatus`、`businessWritable` getters 和末尾扩展参数构造；原构造委托未知/不可写默认值。`PaymentOrderView` 新增 boolean getter 及末尾参数构造，原构造亦为不可写默认值；实际新响应由服务查询真实医院状态后赋值，不默认放行。
- `PaymentService.businessWritable(CurrentActor actor,String orderId) -> boolean` 验证本人订单后读取医院状态，供 PaymentResource 的查询/支付安全视图使用；医院订单视图不授予患者支付能力。

- [ ] **Step 1: 写启停、分页、上下文和字段白名单测试。** 数据含两家医院、有未完成预约、未付/已付订单和独立停用医生；检查：

```java
assertEquals("DISABLED", service.changeStatus(platformActor, tenantId,
    new TenantStatusRequest("DISABLED", "ACTIVE")).getStatus());
assertEquals("DISABLED", service.changeStatus(platformActor, tenantId,
    new TenantStatusRequest("DISABLED", "ACTIVE")).getStatus());
assertThrows(IllegalStateException.class, () -> service.changeStatus(platformActor, tenantId,
    new TenantStatusRequest("ACTIVE", "ACTIVE")));
assertFalse(patientHistory.get(0).isBusinessWritable());
```

  `get...`/`is...` 以 JavaBean 类型一致：boolean 全部采用 `isBusinessWritable()`。验证同名医院按 ID 稳定分页、page>=1、size=1/100 边界、溢出 offset 拒绝、未知请求字段拒绝；平台错误角色 403、不存在私有目标 403。医院重启后独立停用 Account/Department/撤回 Slot 状态不变；上下文不能读另一医院。ObjectMapper 序列化医院列表每项字段严格等于 id/name/status，旧构造及旧业务响应字段仍兼容。
- [ ] **Step 2: 运行 RED。** `mvn -f course/pom.xml -pl paas -am '-Dtest=PlatformTenantTest,TenantContextApiTest,TenantViewPrivacyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；预期状态方法/安全字段断言失败。
- [ ] **Step 3: 实现服务、DTO 和 Resource。** 平台身份由数据库 Account 验证；changeStatus 只取医院写锁并 refresh，先判断已达目标，再核对 expectedStatus；不联动业务行。患者历史和账单真实生成写状态字段。列表参数默认 1/20，范围 1–100，不把分页列表用于统计总计。
- [ ] **Step 4: 运行 GREEN 与完整 PaaS 回归。** 重跑 Step 2 和 `mvn -f course/pom.xml -pl paas -am test`；全部失败/错误为 0，检查安全字段白名单。
- [ ] **Step 5: 精确提交。** 只暂存本项 DTO、服务、Resource、测试；检查 staged diff，提交 `feat(course): add platform hospital lifecycle APIs`。

### Task 3: 有明确日期口径的聚合统计及 API

**Files:**

- Create under `course/contract/src/main/java/com/medcloud/course/contract/`: `StatisticsMetrics.java`, `LegacyUnknownTime.java`, `DailyStatistics.java`, `HospitalStatisticsView.java`, `TenantCounts.java`, `StatisticsReport.java`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/statistics/StatisticsRange.java`, `StatisticsRepository.java`, `StatisticsService.java`（后三者同目录）
- Create: `course/paas/src/main/java/com/medcloud/course/paas/api/StatisticsResource.java`
- Test under `course/paas/src/test/java/com/medcloud/course/paas/`: `StatisticsRangeTest.java`, `StatisticsAggregationTest.java`, `StatisticsApiTest.java`, `StatisticsPrivacyTest.java`

**Interfaces:**

- Consumes: Task 1 身份/医院只读资格；Task 2 `TenantView`。
- `StatisticsRange.parse(String from,String to,Clock clock) -> StatisticsRange`；getFrom/getTo 返回 LocalDate；getStart/getEndExclusive 返回 Instant。默认 Clock 使用 Asia/Shanghai；严格四位年 ISO 格式、空白归一化、最大 366 天；起点和次日排他上界都必须能映射到 MySQL DATE/DATETIME 支持范围（1000–9999 年），因此拒绝止日 9999-12-31，不能溢出到五位年。
- `StatisticsMetrics` 字段名与 spec 表一致：9 个 long 计数和 `orderAmount/paidRevenue` 两个 BigDecimal；静态 `zero()`，`plus(StatisticsMetrics) -> StatisticsMetrics`，返回新值不修改输入，金额 scale=2。
- `LegacyUnknownTime`：`startedAppointments/completedRecords/generatedOrders/paidOrders` 四个 long；`orderAmount/paidRevenue` 两个 BigDecimal；同样 zero/plus。
- `DailyStatistics(String date,StatisticsMetrics metrics)`；`HospitalStatisticsView(TenantView tenant,StatisticsMetrics metrics,LegacyUnknownTime legacyUnknownTime)`；`TenantCounts(long total,long active,long disabled)`。
- `StatisticsReport` getter 字段：scope（HOSPITAL/PLATFORM）、from、to、timezone、generatedAt（带偏移的 ISO 时间字符串）、metrics、daily、hospitals、legacyUnknownTime、tenantCounts；医院返回单院列表及 null tenantCounts，平台返回全部院列表和存量计数。
- `StatisticsRepository(EntityManager)`：`List<TenantView> tenants(String tenantId)`；`Map<String,List<DailyStatistics>> daily(StatisticsRange range,String tenantId)`；`Map<String,LegacyUnknownTime> legacy(String tenantId)`。tenantId=null 仅由已授权平台服务传入，其他值为精确本院限定。采用固定数量的参数化 GROUP BY 查询，不能每医院/每天查询一次。
- `StatisticsService.hospital(CurrentActor,String from,String to) -> StatisticsReport`；`platform(CurrentActor,String from,String to) -> StatisticsReport`；测试构造 `(EntityManager,Clock)`。同一次事务从完整日聚合推导每院/平台指标与每日趋势，补足 0，不重复使用截断历史列表。
- API GET `/hospital/statistics`、`/platform/statistics`，医院入口用 UriInfo 检测并拒绝任何 tenantId 参数（含空值/重复值），真实租户来自 Account。

- [ ] **Step 1: 写范围和数值测试。** 固定 Clock 为 `2026-10-03T04:00:00Z`，默认日期必须 `2026-09-04` 至 `2026-10-03`；验证闰日、上海半开边界、默认时区改为洛杉矶仍正确；有效 366 天通过，367 天/单端/倒置/宽松日期/五位年/数据库越界失败。聚合夹具 A 院 4 条 10-01 下单预约（BOOKED/IN_PROGRESS/CANCELED/COMPLETED 各 1）、10-02 完成一病历并生成 39.10 订单、10-03 支付；B 院 10-02 一条取消预约；C 院无业务且 DISABLED。另加 NULL 时间旧业务；两类数据明确不重叠。断言：

```java
assertEquals(4L, hospital.getMetrics().getAppointmentTotal());
assertEquals(1L, hospital.getMetrics().getAppointmentCanceled());
assertEquals(1L, hospital.getMetrics().getConsultationCompleted());
assertEquals(new BigDecimal("39.10"), hospital.getMetrics().getOrderAmount());
assertEquals(new BigDecimal("0.00"), hospital.getMetrics().getPaidRevenue());
assertEquals(5L, platform.getMetrics().getAppointmentTotal());
assertEquals(3, platform.getHospitals().size());
assertEquals(2, platform.getDaily().size());
```

  上述报告范围为 10-01 至 10-02；旧预约 booked_on 在该范围外，旧事件保持 NULL。另查询 10-03 验证实收 39.10、区间应收 0.00。开始事件按专门设置的 started_at 断言，不把完成群体数当完成事件数。添加 201 条数据专项检查统计不截断、跨租户不匹配关联不能进入订单生成聚合、全零 366 天都有零值日期、NULL 全量提示不计入区间、BigDecimal 0.10+0.20=0.30。
- [ ] **Step 2: 写角色/API/隐私失败测试并运行 RED。** 缺 token 401、停用账号 401、错误角色 403、医院附加 tenantId 400、停用院统计可读；ObjectMapper 白名单逐层验证无患者/私有业务 ID/临床字段。运行 `mvn -f course/pom.xml -pl paas -am '-Dtest=StatisticsRangeTest,StatisticsAggregationTest,StatisticsApiTest,StatisticsPrivacyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；预期新聚合/范围断言失败。
- [ ] **Step 3: 实现范围、聚合和 EJB/API。** 使用原生 SQL 对 booked_on 日期及 started_at/completed_at/paid_at 事件分别 GROUP BY 医院和 DATE，NULL 时间另作全量聚合。订单只与同租户同预约病历关联，未找到完成时间者进入未知生成时间提示。不用临床实体查询、SELECT * 或浮点累加；所有用户日期/租户使用绑定参数。服务在 REQUIRED 事务中执行非锁定一致读；数据库 SQL 聚合列表作为标量 DTO 原料，不从 JPA 一级缓存实体推断状态。
- [ ] **Step 4: GREEN 与 PaaS 回归。** 重跑 Step 2，再 `mvn -f course/pom.xml -pl paas -am test`；验证每日→本院→平台等式和未知时间独立提示，扫描 SQL 投影确认没有正文/身份字段。本项 H2 通过不代表 MySQL 时区/快照已验收。
- [ ] **Step 5: 精确提交。** 检查 staged diff 后提交 `feat(course): add scoped medical cloud statistics`。

### Task 4: Servlet 数据装配、启停表单路由与授权失效处理

**Files:**

- Create: `course/portal/src/main/java/com/medcloud/course/portal/web/PlatformWorkspace.java`, `StatisticsWorkspace.java`, `WorkspaceAccess.java`（后三者同目录）
- Modify: `course/portal/src/main/java/com/medcloud/course/portal/web/PortalServlet.java`, `PaasClient.java`（同目录）
- Read/reference: `course/portal/src/main/java/com/medcloud/course/portal/web/PaasRejectedException.java`（已有 getStatus，不需要无关修改）
- Modify: `course/portal/src/main/java/com/medcloud/course/portal/factory/CloudServiceFactory.java`
- Test: `course/portal/src/test/java/com/medcloud/course/portal/PlatformPortalTest.java`, `StatisticsPortalTest.java`, `WorkspaceAccessTest.java`（同目录）
- Modify tests: `course/portal/src/test/java/com/medcloud/course/portal/HospitalPortalTest.java`, `PortalServletTest.java`, `PortalFlowTest.java`, `SlotSearchPortalTest.java`（同目录）

**Interfaces:**

- Consumes: Task 2/3 JSON DTO，旧 PaasClient get/post/put。
- `PlatformWorkspace(PaasClient)`；`load(HttpServletRequest,String token) -> void` 分区加载医院列表并设置 tenants/tenantListError/page/size；`changeStatus(HttpServletRequest,String token) -> void` 验证表单并调用 PUT；`redirectQuery(HttpServletRequest) -> String` 仅保留合法 page/size/statsFrom/statsTo。
- `StatisticsWorkspace(PaasClient)`；`load(HttpServletRequest,String token,Role role) -> void` 处理独立 statsFrom/statsTo，设置 statistics/statsFrom/statsTo/statisticsError，失败不设置假零报告。
- `WorkspaceAccess(PaasClient)`；`loadContext(HttpServletRequest,String token,Role role) -> void` 获取 hospitalContext，未知或失败时写控件默认禁止；`clearPrivateAttributes(HttpServletRequest) -> void` 清除所有本次已加载的私有区属性与 notice/initialPassword，保留公共目录。
- 复用已有 `PaasRejectedException.getStatus() -> int`，401 向外传播供 Servlet 清理会话，其他错误各区独立显示；PaasClient 保留固定公共错误，不展示服务端 SQL/堆栈。
- Portal 新 POST `/platform/tenant/status`，所有 `/platform/` 子路径只允许 PLATFORM_ADMIN，现有角色 home path 不变。

- [ ] **Step 1: 写 HttpServer+mock Servlet 测试。** GET 页面同时带订单 from/to/status 与 statsFrom/statsTo，断言订单和统计请求各自使用正确参数、统计不发送浏览器 tenantId；启停带 CSRF，断言 PaaS 收到 PUT、UTF-8 JSON 仅 status/expectedStatus，redirect 保留合法列表页和统计日期。错误 CSRF/角色不符时 PaaS 调用数为 0。
- [ ] **Step 2: 写降级/401 测试并运行 RED。** 模拟统计 500 而订单成功，assert orders 非空且 statistics 为 null；模拟先读到 orders 后 context/statistics 返回 401，断言私有 attributes、notice、initialPassword 清空，会话失效并只显示重新登录；context 503 时所有写控件只读但历史仍显示。测试非法统计筛选不调用默认全量查询，reset 只重置统计而保留合法订单条件。运行 `mvn -f course/pom.xml -pl portal -am '-Dtest=PlatformPortalTest,StatisticsPortalTest,WorkspaceAccessTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；预期新路由/属性断言失败。

```java
// statisticsFailureKeepsOrders：设置统计 500、订单成功后调用 servlet.service。
assertNotNull(request.getAttribute("orders"));
assertNull(request.getAttribute("statistics"));
assertNotNull(request.getAttribute("statisticsError"));
// lateUnauthorizedClearsPrivateData：另一用例设置后续接口 401。
assertNull(unauthorizedRequest.getAttribute("orders"));
assertNull(unauthorizedRequest.getAttribute("notice"));
assertNull(unauthorizedRequest.getAttribute("initialPassword"));
assertTrue(unauthorizedSession.isInvalid());
```

- [ ] **Step 3: 实现小单元与 Servlet 编排。** 使用上述 helper，医院科室/医生/号源/订单、医生预约/病历、患者病历/账单各自独立读；任何受保护调用 401 终止本次私有装配并清空，不将其降级为普通区错误。患者病历与账单也独立加载，目录不可用不影响它们。POST 先会话角色与 CSRF，后转发；401 清理成功 flash，不把状态错误当成功。Factory 补齐平台 manage-tenants/view-statistics 和医院 view-statistics 动作，保留旧动作。
- [ ] **Step 4: GREEN 与完整 portal 回归。** 重跑 Step 2，再 `mvn -f course/pom.xml -pl portal -am test`；更新原 mock 服务以提供新增 context/statistics 的合法形状，不删除旧行为断言。全部失败/错误为 0。
- [ ] **Step 5: 精确提交。** 检查 staged diff 后提交 `feat(course): connect hospital management and statistics portal flows`。

### Task 5: JSP 看板、冻结提示及可访问趋势

**Files:**

- Create: `course/portal/src/main/webapp/WEB-INF/views/statistics.jspf`
- Modify under `course/portal/src/main/webapp/WEB-INF/views/`: `platform.jsp`, `hospital.jsp`, `doctor.jsp`, `patient.jsp`, `header.jspf`, `status.jspf`
- Modify: `course/portal/src/main/webapp/assets/course.css`
- Test: `course/portal/src/test/java/com/medcloud/course/portal/StatisticsJspTest.java`, `ReadOnlyWorkspaceJspTest.java`
- Modify: `course/portal/src/test/java/com/medcloud/course/portal/WorkspaceJspTest.java`
- Modify if navigation tests need added cases only: `course/portal/src/test/js/portal.test.cjs`（不无故修改 portal.js）

**Interfaces:**

- Consumes: Task 4 attributes，Task 2 患者 history tenantStatus/businessWritable、账单 businessWritable。
- Produces: 平台 `#tenant-management/#platform-statistics/#daily-trend/#architecture`；医院 `#hospital-statistics/#daily-trend` 和旧业务锚点。statistics.jspf 使用 statistics/statisticsError/statsFrom/statsTo，单页只包含一次，避免重复 ID/JSP 变量。
- GET form name statsFrom/statsTo；启停 POST 包含 id/status/expectedStatus/csrfToken/合法筛选；固定告知文字为“暂停预约、取消、接诊、缴费和资源维护，历史可查”。

- [ ] **Step 1: 写真实 Jasper 渲染失败测试。** 复用嵌入 Tomcat 模式，构造 ACTIVE/DISABLED/context 缺失三种状态，断言历史表仍存在、停用和未知状态没有可提交的写按钮；新患者 DTO 字段与原预约/账单状态共同决定按钮。ACTIVE 夹具必须显式提供可写字段，不能为了旧 fixture 缺字段而默认允许写。
- [ ] **Step 2: 写看板/注入/零值测试并运行 RED。** `StatisticsJspTest` 校验字段含范围/上海时区/指标口径/全量未知时间标签，金额 39.10 以两位显示，366 个零值日期无 NaN/Infinity、HTML 名称转义、表格 tabindex/role/aria-label、真实错误不伪造零统计、平台无临床链接。医院页中统计重置保留订单条件，订单重置保留合法统计条件。运行 `mvn -f course/pom.xml -pl portal -am '-Dtest=StatisticsJspTest,ReadOnlyWorkspaceJspTest,WorkspaceJspTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；预期新只读/看板 HTML 断言失败。

```java
// zeroTrendAndHostileHospitalNameRenderSafely：render 结果来自含 366 天零值的夹具。
assertTrue(html.contains("Asia/Shanghai"));
assertTrue(html.contains("39.10"));
assertTrue(html.contains("&lt;script&gt;unsafe&lt;/script&gt;"));
assertFalse(html.contains("<script>unsafe</script>"));
assertFalse(html.contains("NaN"));
assertFalse(html.contains("Infinity"));
// disabledHospitalKeepsHistoryWithoutWritableActions：另一只读夹具。
assertTrue(disabledHtml.contains("id=\"orders\""));
assertTrue(disabledHtml.contains("历史可查"));
```

- [ ] **Step 3: 实现 JSP 和本地 CSS。** 安全输出 JsonNode 字段，经 WebEscaper 转义，不渲染服务端实体或原始 HTML。表格是完整趋势信息，条形宽度仅由受限非负数计算，最大值为 0 时宽度 0；金额格式用 BigDecimal。新增导航保持架构卡片和原业务入口；表格独立滚动且支持键盘，页面无 390px 整体水平溢出。写控件旁展示只读原因，无 JS 时表单和趋势仍工作。
- [ ] **Step 4: GREEN 与完整 UI 回归。** 重跑 Step 2、`mvn -f course/pom.xml -pl portal -am test`、`node --test course/portal/src/test/js/portal.test.cjs`；JSP 编译成功、全部失败/错误为 0，目录级联与底部短卡片导航原测试继续通过。
- [ ] **Step 5: 精确提交。** 检查 staged diff 后提交 `feat(course): display hospital lifecycle and statistics workspaces`。

### Task 6: 真 MySQL 并发/快照、双容器验收与课程说明

**Files:**

- Create: `course/paas/src/test/java/com/medcloud/course/paas/MySqlTenantLifecycleTest.java`, `MySqlStatisticsTest.java`（同目录）
- Create: `course/deploy/tests/platform-statistics.Tests.ps1`
- Create: `course/deploy/scripts/verify-platform-statistics.ps1`
- Create: `docs/course-labs/2026-10-03-platform-statistics-acceptance.md`
- Modify: `course/deploy/README.md`, `docs/course-labs/README.md`, `docs/course-architecture/README.md`
- Modify only if actual isolation needs correction: `course/deploy/wildfly-datasource.cli`、`course/deploy/tests/configure-wildfly.Tests.ps1`

**Interfaces:**

- Consumes: 全部前项、现有 MySQL integration Maven profile 和运行环境。
- `platform-statistics.Tests.ps1` 从现有 disposable-MySQL Pester 方案提取所需初始化步骤，创建唯一临时 datadir/本机闲置端口；专用数据库 `course_platform_test_<32位小写hex>`，设置 COURSE_MYSQL_TEST_URL/USER/PASSWORD，并在 finally 恢复原环境。
- 新 MySQL Java 测试必须严格验证该 URL/库名模式后才允许 create-drop，不能使用现有运行库。新临时目录完整解析、校验在临时根内且固定前缀后才能清理；后台启动须 Hidden，不打印敏感环境。
- `verify-platform-statistics.ps1 -PaasBaseUrl <url> -FixturePath <local json>` 对专用验收账号/医院执行认证、启停、读写边界、聚合及重新启用核对。fixture 必须是显式本地文件；字段为 tenantId、tenantName、accounts（platform/hospital/doctor/patient 各含 username/password）、resources（slotId/departmentId/doctorUserId/bookedAppointmentId/inProgressAppointmentId/completedAppointmentId/unpaidOrderId/paidOrderId）。ID 均为 UUID；医院名必须以 `course_stats_acceptance_` 开头，调用前从平台分页列表核实名称和 ID，禁止目标为既有两家 demo 医院。凭据不进入 Git/报告；脚本输出只限聚合和通过/失败，finally 按原状态恢复目标医院。

- [ ] **Step 1: 写 MySQL 两事务测试。** `businessLockDelaysDisable` 用 latch 确认医院读锁已取，再通过 EntityManager proxy 在竞争线程调用医院写锁前发出信号，确认线程确实到达竞争点后断言启停等待，业务释放后停用才成功；`disableCommittedBeforeStaleWriterRejectsMutation` 先建立普通 ACTIVE 快照、另一事务停用提交，再调用写服务，必须 refresh 后拒绝。参数化覆盖预约、取消、开始/完成、支付、科室/医生/号源 create/update；用 proxy/latch 观察锁顺序，不靠长 sleep 推断并发。Future 和锁超时有上限，线程不共享 EntityManager，失败时全部回滚并终止线程。
- [ ] **Step 2: 写 MySQL 统计一致性测试。** 与 Task 3 同一日期/金额夹具核对实际 DATE/DATETIME SQL。`reportUsesOneRepeatableReadSnapshot` 在首个聚合后让另事务提交支付/医院状态变更，本次报告仍来自一致旧快照，下次新事务看到新结果；平台总计/每日/分院/存量计数每次自洽。真实 NULL 时间和上海午夜边界有独立断言。

```java
// businessLockDelaysDisable：disable Future 已启动，writer 已持医院读锁。
assertThrows(TimeoutException.class, () -> disableFuture.get(200, TimeUnit.MILLISECONDS));
// 释放业务事务闩锁后，再检查停用成功；每个 Future 总等待上限 10 秒。
assertEquals("DISABLED", disableFuture.get(10, TimeUnit.SECONDS).getStatus());
// reportUsesOneRepeatableReadSnapshot：首个查询快照为未付款，竞争者已付款。
assertEquals(new BigDecimal("0.00"), firstReport.getMetrics().getPaidRevenue());
assertEquals(new BigDecimal("39.10"), nextTransactionReport.getMetrics().getPaidRevenue());
```

- [ ] **Step 3: 运行新测试，补修与 GREEN。** `Invoke-Pester -Path course/deploy/tests/platform-statistics.Tests.ps1 -Output Detailed`；脚本内部执行 `mvn -f course/pom.xml '-Dtest=MySqlTenantLifecycleTest,MySqlStatisticsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。预期新增 MySQL 测试 0 skipped、0 failure/error；没有真实 MySQL 就标记未验证，不能以默认 skip 通过作为验收。若触发服务缺陷，先依 systematic-debugging 定位并保留回归再修复。
- [ ] **Step 4: 全量构建与部署。** 清除测试环境变量后 `mvn -f course/pom.xml clean verify`、`node --test course/portal/src/test/js/portal.test.cjs`；保存真实汇总与跳过原因。只备份/更新隔离运行目录现有 WAR，不重新初始化数据库；核实 JVM/JDBC 上海时区和实际数据源 REPEATABLE READ，必要配置修改后另测，不改老师安装目录。
- [ ] **Step 5: 专用 fixture 验收。** 在运行库新增专门虚构验收医院/账号/业务（非产品新医院入口），执行 verify 脚本及白名单 SQL 聚合对账；停用后原会话/新登录历史可查、全部写入口失败，启用后未过期正常写恢复而独立停用资源不恢复。保留限定 fixture 数据作报告证据，finally 恢复测试医院原状态，不停用两家既有演示医院。
- [ ] **Step 6: 页面与无 JS 验收。** 依 computer-use 技能读取 UI 规则，通过原 IAB 展示平台医院表格/启停、本院与跨院趋势、冻结提示和恢复，验收桌面与 390×844；真实禁 JS 或至少 authenticated raw HTTP GET/POST 验证无 JS 表单链路，明确区分两种证据。查看目录停用后仍可直达历史详情，验证 CSRF 拒绝和统计故障分区降级。截图/日志存 `.superpowers/platform-statistics-qa/`（不提交敏感运行夹具）。
- [ ] **Step 7: 写验收记录与说明。** 文档逐项映射 FR-TEN-05/FR-STAT-01/02/AT-07，列实测计数、SQL 对账、真实并发/隔离证据及未验证项；更新部署/API/课程分层说明，不宣称完成附件、审计或医疗合规。
- [ ] **Step 8: 最终评审、修复回归和提交。** 使用 requesting-code-review 对本轮全分支请求独立审查，重点覆盖完整写入口、跨租户边界、快照/NULL/日期和门户 401；确认 Critical/Important 已解决并重跑受影响测试及完整 verify 后精确提交测试/说明，提交 `test(course): verify hospital lifecycle and statistics end to end`。不自动合并或 push。

## 计划自检与交接

设计 §1–2 对应全局约束/结构；§3 对应 Task 1/2/6；§4–5 对应 Task 3/6；§6 对应 Task 2/3；§7 对应 Task 4/5；§8–9 对应 Task 6。Review Focus 的五类输入各已有所属任务测试；跨任务 DTO 属性、boolean getter、服务签名保持统一。实现者仍须同时通读 spec，不得只按本计划摘要修改。

当前所有复选项未执行：本文件是待用户审阅的实施计划，不是功能完成记录。沿用用户此前指定的子代理驱动方式；计划确认后由新实现子代理逐项执行并经规格/代码评审，主代理负责接口一致性、完整验证及最终交付。不为本计划创建新用户聊天任务。
