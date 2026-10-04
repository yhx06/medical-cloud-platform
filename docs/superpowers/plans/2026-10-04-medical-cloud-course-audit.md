# 医疗云课程版持久化审计中心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在课程版实现13类关键操作的安全持久化审计、授权分页查询和门户展示，证明业务/审计事务一致，不减少全项目其他已设计功能。

**Architecture:** 在现有 contract/paas/portal 内交付纵向切片。业务EJB与 MANDATORY 审计写入同事务，JAX-RS调用失败返回后使用独立 REQUIRES_NEW EJB记录失败；请求上下文显式传递。授权查询只返回安全投影，Tomcat分区加载并独立降级。

**Tech Stack:** Java 8、javax Java EE 7、Tomcat 9、WildFly 10、JPA 2.1、MySQL、JUnit 5、现有 RESTEasy/H2 测试依赖、PowerShell/Pester、Node内置测试；不新增产品框架。

**Spec:** `docs/superpowers/specs/2026-10-04-medical-cloud-course-audit-design.md`（用户已确认，取消后续PowerDesigner绘图）。

## Global Constraints

- 使用已有隔离工作树 `C:/Users/yhx/.codex/worktrees/course-environment/云服务项目-医疗云` / `codex/course-environment`，不在E:脏工作树开发，不自动合并/push。
- 只修改 `course/` 与直接相关说明；不改原 backend/frontend、无关 API Probe.eam 或其他PowerDesigner模型/图/生成器/验证器。
- 13事件为LOGIN及设计第3节12个写入口；结果精确为SUCCESS/NO_CHANGE/FAILED，原因只用OK/UNCHANGED及现有五类错误码。
- audit_event字段、长度、空值、索引、唯一键按设计第4节；无业务外键、自由文本或临床/凭据内容。DTO无actor_id/object_id或临床明细入口。
- 保留医院业务锁顺序、冻结/历史只读、原状态机、首次支付时间、外部API路径/成功体与全部已有测试断言；不能以无操作审计实现绕过生产记录。
- 认证先于新查询参数解析；日期上海近30天默认、1–366日；page=1/size=20，size 1–100，long offset≤Integer.MAX_VALUE。
- JPA生产保持validate；只加表，不重跑初始化/seed或重写旧业务。现有运行库不默认升级、WAR不自动覆盖、教师安装不改。
- 所有新数据虚构；凭据/备份/临时运行证据只存ignored本地文件，不打印密码/JWT/临床正文。Windows新后台进程隐藏窗口，清理必须核对精确临时目标和进程身份。
- 每项任务先RED再GREEN，独立提交，仅暂存该任务文件；测试用直接调用不能冒充真实EJB/JTA代理证据。
- 7项任务完成后仅一次整体评审，再集中修复/限定复审；最终结论必须列明跳过和未执行检查。

## Review Focus

1. 登录旧测试曾在无RESOURCE_LOCAL事务时调用login；Task4改成显式事务夹具，不能让生产成功审计变成可选或no-op。
2. 支付等操作提交成功后还有视图装配/额外读取；Task2/5把业务执行与展示分开，Task7验证后置故障不制造FAILED。
3. 用户伪造跨院对象ID及重复tenantId；Task3/5分别验证身份优先、拒绝重复参数、失败归属不能使用目标ID。
4. 失败记录另起事务可能自调用失效或仍等待业务锁；Task2用独立EJB，Task7以真实容器与受控数据库触发器证明原事务已结束。
5. 新审计页遇到401、500、超时及长UUID/中文内容；Task6/7验证完整清理、独立降级和390px/键盘滚动，不把服务故障显示为空结果。

## 文件结构与公共接口

路径以下列前缀展开，前缀只是文档缩写，不是新目录：

- `C` = `course/contract/src/main/java/com/medcloud/course/contract/`
- `P` = `course/paas/src/main/java/com/medcloud/course/paas/`
- `PT` = `course/paas/src/test/java/com/medcloud/course/paas/`
- `W` = `course/portal/src/main/java/com/medcloud/course/portal/web/`
- `WT` = `course/portal/src/test/java/com/medcloud/course/portal/`
- `V` = `course/portal/src/main/webapp/WEB-INF/views/`

Task1交付迁移；Task2定义审计持久化/上下文/边界；Task3定义查询契约；Task4/5消费Task2并接入业务；Task6消费Task3契约；Task7验证所有产物。顺序执行，避免多个代理同时编辑资源、mapper或PortalServlet。

## Task 1：只加表迁移及保护性升级

**Files:** Create `course/deploy/migrations/002_audit_events.sql`、`course/deploy/scripts/upgrade-audit-database.ps1`、`course/deploy/tests/upgrade-audit-database.Tests.ps1`；Modify `course/deploy/schema.sql`、`course/deploy/README.md`；必要时仅适配 `course/deploy/tests/upgrade-p0-database.Tests.ps1` 的旧库夹具构造。

**Interfaces:** Produces新库/旧库相同的audit_event结构；升级脚本参数为原脚本同名 `MySqlCli/MySqlDumpCli/HostName/Port/Database/ExpectedServerUuid/BackupPath`。Database仅medical_cloud_course；凭据沿用MEDICAL_COURSE_DB_USER/PASSWORD，恢复MYSQL_PWD。

- [ ] Step1：写失败测试 `validP0ShapeAddsOnlyAuditTable`、`backupFailurePreventsDdl`、`wrongUuidPortDatabaseOrLegacyShapeRefusesWrites`、`completeAuditShapeIsReadOnlyAlreadyUpgraded`、`partialOrForeignAuditShapeRefusesRepair`。断言原8表数据摘要不变、新表11列/指定索引/无外键、备份先于DDL、重复执行不写、凭据不输出。新鲜专用MySQL实例内的规范库名用于真实迁移，不触碰3318运行库。

  核心断言（变量由同一测试的information_schema查询及业务摘要取得）：
  ```powershell
  @($auditColumns).Count | Should Be 11
  @($auditForeignKeys).Count | Should Be 0
  $businessDigestAfter | Should BeExactly $businessDigestBefore
  ```
- [ ] Step2：运行 `Invoke-Pester -Script course/deploy/tests/upgrade-audit-database.Tests.ps1 -PassThru`，记录缺失升级器/结构导致的RED，不以未启动MySQL或路径错误当需求失败。
- [ ] Step3：实现完全基线核对、目标UUID/端口核验、全库备份成功才CREATE TABLE；新schema与迁移使用InnoDB/utf8mb4、DATETIME(6)，索引按spec。正确已升级停止写，残缺表拒绝，不DROP/恢复覆盖；失败提示不含数据库stderr凭据。
- [ ] Step4：重跑上述Pester，要求全部0失败/0跳过，核对备份内容、业务摘要和所有信息schema索引；既有 `upgrade-p0-database.Tests.ps1` 若因新schema构造旧夹具需定点兼容，不改变原升级规则/断言。
- [ ] Step5：只暂存本任务SQL/脚本/测试/说明，检查diff后提交 `feat(course): add protected audit table migration`。不得执行主运行库升级。

## Task 2：审计实体、事务端口与请求边界

**Files:** Create `C/AuditEventType.java`、`C/AuditResult.java`、`C/AuditReasonCode.java`、`C/AuditObjectType.java`；Create `P/audit/AuditEvent.java`、`AuditContext.java`、`AuditWritePort.java`、`AuditJournal.java`、`AuditFailureJournal.java`、`AuditInfrastructureException.java`、`AuditOperationRunner.java`、`AuditedOperationException.java`；Create `P/api/AuditRequestIds.java`、`AuditTracked.java`；Modify `P/api/ApiFailureMapper.java`、`course/paas/src/main/resources/META-INF/persistence.xml`、`course/paas/src/test/resources/META-INF/persistence.xml`；Test `PT/AuditJournalTest.java`、`AuditOperationRunnerTest.java`、`AuditRequestIdsTest.java`。

**Interfaces:**

- `AuditContext.create(AuditEventType event, String serverRequestId)`；`attachActor(CurrentActor verifiedActor)`；`attachVerifiedTenant(String tenantId)`；`getRequestId()`。event决定objectType，禁止接收请求body/原始目标ID/用户名。actor仅在AuthService验证后attach；tenant仅在服务核验真实归属后attach。requestId必须服务端UUID，测试可显式注入固定合法UUID。
- `AuditWritePort.append(AuditContext context, String verifiedObjectId, AuditResult result, Instant recordedAt): void`，仅SUCCESS/NO_CHANGE；AuditJournal为Stateless/MANDATORY实现，实际persist/flush失败抛 `AuditInfrastructureException`（ApplicationException rollback=true）。构造器测试可注入EntityManager，但不能生产默认no-op。
- `AuditFailureJournal.append(AuditContext context, AuditReasonCode reason, Instant recordedAt): void`，Stateless/REQUIRES_NEW，仅FAILED且objectId永远null，不查询业务实体。
- `AuditOperationRunner.execute(AuditContext context, AuditAction<T> action): T`；嵌套functional interface `AuditAction<T>.execute(AuditContext context): T`。runner为Stateless/NOT_SUPPORTED，resource传入认证+一个业务EJB调用；失败映射/独立失败记录后抛 `AuditedOperationException(originalFailure,requestId)`。绝不把后置DTO装配包含进action。runner不得在NOT_SUPPORTED中使用EntityManager写业务，只调用真实业务/失败EJB代理。
- `AuditRequestIds.ensure(ContainerRequestContext): String` 从受控属性取得或新建UUID，不读客户端X-Request-ID。`AuditTracked`为Runtime/TYPE+METHOD的JAX-RS NameBinding注解，仅标注13写方法及2新查询；对应请求/响应filter分别调用ensure与设置响应头，资源显式取ID，mapper保留同ID。无容器的直接资源测试使用显式测试requestId或新建ID，不用全局/threadlocal用户缓存。

- [ ] Step1：写 `successfulAppendRequiresTransactionAndUsesScalarWhitelist`、`rollbackRemovesSuccessRow`、`duplicateRequestEventIsRejected`、`failureAlwaysHasNullObjectId`、`failureWriterThrowsWithoutChangingOriginalFailure`、`postActionPresentationFailureIsNotBusinessFailure`、`spoofedHeaderIsIgnoredAndParallelContextsDoNotMix`。断言UUID/枚举/11字段、actor/tenant快照、敏感哨兵不入实体/安全日志；无变化原因UNCHANGED；认证失败类型正确解包EJBException。

  核心断言：`assertNull(failureEvent.getObjectId()); assertEquals(serverId, context.getRequestId()); assertNotEquals(spoofedHeaderId, context.getRequestId());`；实体结果/事件/原因getter返回对应contract枚举，不混用字符串与枚举。
- [ ] Step2：运行 `mvn -f course/pom.xml -pl paas -am '-Dtest=AuditJournalTest,AuditOperationRunnerTest,AuditRequestIdsTest' -Dsurefire.failIfNoSpecifiedTests=false test`，保留缺类/期望行为RED。
- [ ] Step3：实现这些精确接口，注册AuditEvent到JTA/RESOURCE_LOCAL持久化单元。mapper先解包AuditedOperationException/EJB原因，再保留现有401/403/400/409/500，审计基础设施故障为500；审计故障日志仅安全ID/类型。失败writer调用走独立EJB引用，不自调用；runner不持业务事务。不为GET/反序列化前404/415写失败事件。
- [ ] Step4：跑上述测试和 `ApiFailureTest`，全部GREEN；验证生产无可关闭审计的配置/空实现。原直接资源构造器继续可测试，若需要构造器依赖注入，必须提供真实测试journal/失败端口，不改变外部HTTP。
- [ ] Step5：提交 `feat(course): add transactional audit journal and request boundary`，只包含本任务文件；本任务H2结果不能声称MANDATORY/REQUIRES_NEW已在容器证明。

## Task 3：医院/平台授权查询及契约

**Files:** Create `C/AuditEventView.java`、`C/AuditPage.java`；Create `P/audit/AuditQuery.java`、`AuditQueryService.java`；Create `P/api/AuditResource.java`；Test `PT/AuditQueryTest.java`、`AuditPrivacyTest.java`、`AuditDispatchTest.java`（内部fixture用合法UUID医院）；必要时定点Modify `P/api/HospitalResource.java` 将医院方法归入现有类级路径，避免RESTEasy遮蔽。

**Interfaces:**

- `AuditEventView`仅getters：`id/requestId/tenantId/tenantName/actorRole/actorLabel/eventType/objectType/result/reasonCode/recordedAt`；recordedAt为带+08:00字符串；无个人/业务对象ID。
- `AuditPage(List<AuditEventView> items,long total,int page,int size,String from,String to,String timezone)`，只暴露上述字段，timezone=Asia/Shanghai。
- `AuditQuery.parse(MultivaluedMap<String,String> parameters, boolean platform): AuditQuery`；严格参数白名单/重复拒绝，提供getFrom/getTo/getEventType/getResult/getTenantId/getPage/getSize/getOffset；`getLowerInclusive()/getUpperExclusive(): java.sql.Timestamp`为Shanghai时间边界，最多366天。
- `AuditQueryService.requireReader(CurrentActor actor,boolean platform): void`，供resource在parse前检查数据库当前账号/角色及医院归属；`hospital(CurrentActor actor,AuditQuery query): AuditPage` / `platform(CurrentActor actor,AuditQuery query): AuditPage`查询时再次验证，防止两次调用间状态改变/直接绕过。医院只用数据库所属医院，平台可合法现存tenantId；服务COUNT/列表同一REQUIRED只读快照。
- `GET /hospital/audit-events` 放入现有HospitalResource的 `@GET @Path("audit-events")`；AuditResource使用类级 `@Path("platform/audit-events")` 提供平台GET。成功ApiResponse<AuditPage>；raw字符串/multimap不由RESTEasy提前数值转换，错误按spec。

- [ ] Step1：写 `hospitalSeesOnlyOwnTenantIncludingDisabled`、`platformSeesGlobalOrSelectedExistingTenant`、`disabledActorRejected`、`queryDoesNotLoadPrivateEntities`、`dtoDoesNotExposeActorOrObjectIds`、`countsAndRowsShareFilter`，覆盖空结果/稳定倒序/分页超末页；日期双缺省30天、单端/伪闰日/366与367/极端日期、非法枚举、UUID、未知与重复参数。

  核心断言：`assertEquals(20, defaultQuery.getSize()); assertEquals(1, defaultQuery.getPage()); assertFalse(serializedRow.has("actorId")); assertFalse(serializedRow.has("objectId"));`；dispatcher身份优先断言使用真实HTTP `assertEquals(401, anonymousResponse.getStatus());`。
- [ ] Step2：写真实RESTEasy mock dispatcher `anonymousBadPaginationIs401`、`wrongRoleBadFilterIs403`、`adminBadOrDuplicateTenantFilterIs400`，确保两条路由与原hospital订单/统计路由同时存在；运行 `mvn -f course/pom.xml -pl paas -am '-Dtest=AuditQueryTest,AuditPrivacyTest,AuditDispatchTest' -Dsurefire.failIfNoSpecifiedTests=false test` 得到RED。
- [ ] Step3：实现白名单投影与参数化WHERE，不加载Account/ClinicalRecord内容；角色校验之后才parse，再传EJB；只选医院标签，NULL医院展示全局/归属未验证。稳定排序recordedAt DESC/id DESC，long组合offset校验。
- [ ] Step4：重跑三类新测试与 `HospitalRoutingTest,PlatformTenantDispatchTest` 全部GREEN；同请求响应头/错误体ID一致，查询不产生审计。并发COUNT/列表实机快照证明留给Task7。
- [ ] Step5：提交 `feat(course): add authorized audit queries`，同步 `docs/course-architecture/README.md` 新接口与安全字段说明，不触碰模型/图。

## Task 4：登录与机构资源操作审计

**Files:** Modify `P/auth/AuthService.java`、`P/tenant/PlatformTenantService.java`、`HospitalDepartmentService.java`、`HospitalDoctorService.java`、`P/schedule/SlotAdminService.java`；Modify `P/api/AuthResource.java`、`PlatformTenantResource.java`、`HospitalResource.java`、`HospitalSlotResource.java`；Test `PT/AuditManagementTest.java`、`AuditLoginTest.java`；Adapt相关旧测试/fixture的显式事务（TenantFixture实际定义在TenantReadOnlyTest.java中）。

**Interfaces:** Consumes Task2全部接口。为每个已有登录/create/update/changeStatus方法增加末参数 `AuditContext context` 的重载，返回类型和原参数不变；旧便利方法委托新方法并创建合法服务端context。每个生产EJB注入 `AuditWritePort`，直接EntityManager测试构造器使用真实AuditJournal。resource在runner内认证、attachActor、调用一次业务EJB；返回成功DTO在runner外装配。

- [ ] Step1：写参数化测试 `eachManagementOperationProducesExactlyOneCorrectEvent`（TENANT_STATUS_CHANGE、DEPARTMENT/DOCTOR/SLOT CREATE/UPDATE共7类）和LOGIN成功；断言对象类型/真实目标ID/医院/操作者/时间，账号初始密码与临床哨兵不出现。登录患者/平台tenant为null，医生/管理员为自身医院。

  核心断言：`assertEquals(1L, matchingRequestEventCount); assertEquals(AuditEventType.LOGIN, loginEvent.getEventType()); assertNull(patientLoginEvent.getTenantId());`；原initialPassword不进入审计序列化或日志文本。
- [ ] Step2：写 `badLoginIsAnonymousRegardlessOfUsernameExistence`、`invalidTokenManagementFailureHasNoActorOrTenant`、`managementCrossTenantFailureNeverKeepsRequestedObjectId`、`repeatDesiredHospitalStateIsNoChange`、`journalFailureRollsBackEveryManagementMutation`。实现前运行 `mvn -f course/pom.xml -pl paas -am '-Dtest=AuditManagementTest,AuditLoginTest' -Dsurefire.failIfNoSpecifiedTests=false test` 确认RED。
- [ ] Step3：接入8类事件，事务内真实归属校验后append；合法无状态变化可NO_CHANGE，不放松原状态/资源引用规则。登录签发成功前写审计，失败匿名；authenticate不记LOGIN。补原H2夹具登录的显式begin/commit，不能由生产服务手动管理RESOURCE_LOCAL事务或跳过成功记录来让旧测试通过。
- [ ] Step4：运行新增测试及 `RegistrationTest,PlatformTenantTest,PlatformTenantDispatchTest,HospitalResourceTest,HospitalSlotTest,TenantReadOnlyTest,TenantWriteFreezeTest` GREEN，保证注册不新增审计、冻结拒绝原业务无改动、旧HTTP体兼容。审计设施失败须500而非409；遗留成功调用不得无事务静默继续。
- [ ] Step5：提交 `feat(course): audit login and management operations`。测试可改事务准备，不能弱化原权限/状态断言。

## Task 5：预约、接诊和模拟支付审计

**Files:** Modify `P/appointment/BookingService.java`、`P/consultation/ClinicalService.java`、`P/order/PaymentService.java`；Modify `P/api/AppointmentResource.java`、`ClinicalResource.java`、`PaymentResource.java`；Test `PT/AuditClinicalFlowTest.java`、`AuditFailureScopeTest.java`；必要时Adapt既有clinical/payment测试事务夹具。

**Interfaces:** 与Task4相同约定，为book/cancel/start/complete/pay已有签名增加最后AuditContext参数，旧方法委托新方法并创建对应context；内部多便利重载只记录一次。runner只包认证与一次业务调用，不包ClinicalRecordView/PaymentOrderView及后置businessWritable读取。

- [ ] Step1：写 `bookCancelStartCompletePayHaveCorrectTenantAndObject`（5类事件），`completionWritesOneEventNotTwo`、`repeatPaymentIsNoChangeWithOriginalPaidAt`、`repeatCompleteStillConflictsWithoutExtraRows`；断言完成预约/病历/订单/审计一起提交，敏感临床哨兵只留在病历，不进入audit或公共日志。

  核心断言：`assertEquals(AuditResult.NO_CHANGE, secondPaymentEvent.getResult()); assertEquals(firstPaidAt, orderAfterRetry.getPaidAt()); assertEquals(1L, ordersForAppointment);`；失败回滚后业务摘要等于调用前摘要。
- [ ] Step2：写 `patientDeniedTargetDoesNotSupplyFailureTenant`、`patientVerifiedOwnedTargetFailureUsesTrustedTenantButNullObjectId`、`doctorCrossTenantFailureBelongsOnlyToAuthenticatedHospital`、`postCommitViewFailureKeepsSuccessOnly`、`auditInsertFailureRollsBackBookingCancelStartCompleteAndPay`。运行 `mvn -f course/pom.xml -pl paas -am '-Dtest=AuditClinicalFlowTest,AuditFailureScopeTest' -Dsurefire.failIfNoSpecifiedTests=false test` 得RED。
- [ ] Step3：在原锁/当前读检查完成后attachVerifiedTenant，更新后append；失败不保存原路径ID、不额外查询目标。取消/开始/完成保持原状态机，已停用医院不能写。重复已付成功为NO_CHANGE且仍先医院冻结校验；不创建第二订单或改首次时间。
- [ ] Step4：重跑新增及 `ClinicalPaymentFlowTest,CancellationTest,BookingBoundaryTest,BookingIsolationTest,TenantWriteFreezeTest` GREEN；注入后置展示故障时runner不捕获成业务失败。真实EJB回滚和失败独立提交仍由Task7证明。
- [ ] Step5：提交 `feat(course): audit booking consultation and payment`。完成后逐表检查13类完整接入清单，不能仅证明各模块有一类事件。

## Task 6：审计门户与私有数据清理

**Files:** Create `W/AuditWorkspace.java`、`V/audit.jspf`；Modify `W/PortalServlet.java`、`WorkspaceAccess.java`、`V/header.jspf`、`hospital.jsp`、`platform.jsp`、`course/portal/src/main/webapp/assets/course.css`；Test `WT/AuditPortalTest.java`、`AuditJspTest.java`；Modify `course/portal/src/test/js/portal.test.cjs`，仅有必要时Modify assets/portal.js。

**Interfaces:** `AuditWorkspace(PaasClient paas)`；`load(HttpServletRequest request,String token,Role role): void`；`static query(HttpServletRequest request,Role role): String`。成功request属性auditPage及auditFrom/auditTo/auditEventType/auditResult/auditPageNumber/auditSize/auditTenantId；错误auditError。query只产生编码后的合法PaaS查询，无非法参数自动退回全量行为。

- [ ] Step1：写 `hospitalAndPlatformLoadOwnAuditEndpoint`、`auditFiltersAreIndependentOfStatsOrdersAndTenantPagination`、`invalidFiltersDoNotRequestDefaultRange`、`audit500OrTimeoutRetainsOtherSections`、`audit401ClearsAllPrivateAttributes`、`auditDtoShapeFailureShowsErrorNotEmptyRows`。模拟PaaS返回缺items/total/from/to字段拒绝；后台role校验，不仅隐藏导航。

  核心断言：`assertNotNull(request.getAttribute("auditError")); assertSame(originalOrders, request.getAttribute("orders")); assertNull(request.getAttribute("auditPage"));`；401后orders/statistics/auditPage和全部新筛选属性均为null，不以局部清空通过。
- [ ] Step2：写JSP嵌入Tomcat测试 `escapedLabelsAndLongRequestIdsFitScrollableRegion`、`emptyVersusUnavailableAreDistinct`、`ordinaryGetFormsAndPaginationPreserveFiltersWithoutScripts`，断言无临床链接/actorId/objectId、正确中文说明、可聚焦scroll区/表头、无假零。运行 `mvn -f course/pom.xml -pl portal -am '-Dtest=AuditPortalTest,AuditJspTest' -Dsurefire.failIfNoSpecifiedTests=false test` 得RED。
- [ ] Step3：仅医院/平台doGet加入独立audit load，401重抛现有过期路径，将全部新私有属性加入WorkspaceAccess清理。普通GET筛选/重置/翻页，平台选院由授权医院目录获取；不借平台医院分页截断丢失选中医院，已选择医院需回显对应安全标签。医院不接受auditTenantId，所有标签转义。
- [ ] Step4：新测试GREEN；运行 `mvn -f course/pom.xml clean verify` 和 `node --test course/portal/src/test/js/portal.test.cjs`，已有导航高亮/最后区域/滚动6测试继续成立，新audit锚点有回归。桌面/390px实际布局由Task7验，不把HTML断言当截图。
- [ ] Step5：提交 `feat(course): add audit workspace and privacy-safe portal`，不把无关UI或PowerDesigner变更混入。

## Task 7：真实MySQL/EJB/门户验收与交付

**Files:** Create `PT/MySqlAuditTransactionTest.java`、`course/deploy/tests/audit-acceptance.Tests.ps1`、`course/deploy/scripts/verify-audit.ps1`；Modify `course/deploy/README.md`、`docs/course-labs/README.md`、`docs/course-labs/2026-10-04-full-scope-inventory.md`；Create `docs/course-labs/2026-10-04-audit-acceptance.md`。运行夹具、临时容器副本、QA代码/日志/截图只在ignored `.superpowers/sdd/2026-10-04-medical-cloud-course-audit/`。

**Interfaces:** MySqlAuditTransactionTest沿用COURSE_MYSQL_TEST_URL/USER/PASSWORD，接受且验证唯一loopback disposable `course_audit_test_<32hex>` 库；生产库拒绝create-drop。verify-audit.ps1接受 `PaasBaseUrl/PortalBaseUrl/FixturePath`，只操作专用虚构医院/账号/资源；fixture不打印，不接受UNC/相对路径。Pester统计预先固定应执行的最低8项MySqlAuditTransactionTest、零failure/error/skip，测试数缺失不得PASS。

- [ ] Step1：写8项真实MySQL测试：`committedSuccessVisible`、`rolledBackMutationHasNoSuccess`、`forcedSuccessInsertFailureRollsBackBusiness`、`duplicateRequestEventIsRejected`、`failureStoresNoUntrustedObject`、`shanghaiMidnightRangeCorrect`、`repeatPaymentKeepsTimestampAndNoChange`、`countAndRowsShareRepeatableReadSnapshot`。执行Pester记录RED，再实现独立实例harness；正常SQL SHUTDOWN并核验精确process/datadir退出后清理，恢复环境变量，不使用运行库或全局强杀。

  核心报告断言：`[int]$report.testsuite.tests | Should BeGreaterThan 7`，skipped/failures/errors各为0；数据库故障请求断言业务摘要未变、该requestId只有FAILED且目标ID为NULL。
- [ ] Step2：重跑harness达到8/8且零skip；并重跑新增/原锁/统计真实专项，检查总测试数与被执行名称。调整断言只围绕新增audit表，不改变原业务真实性。所有SQL/错误输出经白名单，禁止dump临床或凭据到日志。
- [ ] Step3：建立新的ignored临时Tomcat9/WildFly10/MySQL副本，MySQL内新建规范medical_cloud_course库以完成升级器测试，端口取loopback空闲值，不复用8180/8188/3318、不改教师安装/现有数据源/运行库。验证新库和P0旧库升级得到相同schema，再部署本次两个WAR，JVM/JDBC Shanghai且JTA隔离实际为REPEATABLE_READ。保存WAR/配置哈希及启动成功标记；有管理端口冲突时停止排查，不重启原进程。
- [ ] Step4：13类事件逐项真实API演示并SQL对账；使用仅限本专用库的临时BEFORE INSERT触发器，条件为指定验收actor/event且result为SUCCESS/NO_CHANGE，SIGNAL失败以驱动生产AuditJournal失败。核验500、业务整笔回滚、同requestId的独立FAILED仍可持久化；原业务EJB必须已结束，记录事务/锁释放证据。登录成功插入失败不返回令牌；只破坏FAILED插入的触发器验证错误登录仍401且安全运行告警。finally移除精确本次触发器，不能在现有运行库注入故障。
- [ ] Step5：如验证提交后展示异常，临时nonce保护QA WAR使用生产runner/EJB实现并在成功返回后显式抛展示异常，assert只有SUCCESS，无FAILED；记录这是QA包装边界，不声称生产REST被原地注入。并发请求核验requestId/actor/tenant不串；默认筛选、越权/重复参数/大offset和真实RESTEasy路径逐一实测。
- [ ] Step6：真实Tomcat两管理员查看审计，平台按院/全局、本院停用历史、空态/筛选分页；桌面与390px、键盘滚动和实际无脚本GET表单验证。nonce保护loopback代理仅对audit GET注入500与超过客户端read timeout，其他资源真实透传，核验分区保留/无假空；模拟401清理无残留、恢复后可重新读取。只改临时门户context，不改原/portal。结束关闭精确QA服务和新容器，保留验收记录，不批量删业务。
- [ ] Step7：运行最终 `mvn -f course/pom.xml clean verify`、Node、Pester专项；记录实际通过/跳过数量和环境。更新公开接口/部署/隐私/迁移说明、需求清单状态和审计专项证据，说明既有PowerDesigner模型为历史版本、不更新。报告不得包含未执行的MySQL5.6/教师机器/云端承诺。
- [ ] Step8：提交 `test(course): verify audit transactions and portal end to end`；只包含代码测试/说明，ignored凭据/备份/运行产物不得暂存。保留Task1–7记录供唯一整体评审；按审查结论修复并限定复审，不自动push/合并，不把审计阶段完成当全项目完成。

## 规格覆盖与交接

设计§1–2由全局约束和文件边界覆盖；§3的13事件与NO_CHANGE由Task4/5；§4数据和隐私由Task1/2/3；§5事务/关联/失败范围由Task2/4/5及Task7真实验证；§6授权查询由Task3；§7交互与降级由Task6/7；§8保护性迁移由Task1/7；§9每项测试要求已落到对应任务；§10交付由Task7。用户取消PowerDesigner绘图已贯穿spec/计划/完整范围清单。

执行前用户审阅本计划；执行方式沿用已选择的子代理驱动，不重复询问。计划获确认后，再由相应技能建立独立SDD任务记录并逐项派发；当前仅本地提交设计修订与计划，不修改产品、不升级运行库、不push。
