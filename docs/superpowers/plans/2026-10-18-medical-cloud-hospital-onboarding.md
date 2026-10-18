# Hospital Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在课程平台页完成医院与首管理员原子开通、管理员增补/分页/启停，保护密码、重复请求及最后启用管理员。

**Architecture:** 复用现有账户、医院、审计与双容器架构；新增平台管理 EJB、专用 DTO/Servlet 与一张幂等表。医院管理员停用与既有医院写入口通过同院锁协调，不引入新服务。

**Tech Stack:** Java 8、Tomcat 9 Servlet/JSP、WildFly 10 JAX-RS/EJB/JPA、MySQL、现有 BCrypt/Jackson/JUnit；不升级依赖。

**Spec:** [2026-10-18-medical-cloud-hospital-onboarding-design.md](../specs/2026-10-18-medical-cloud-hospital-onboarding-design.md)，设计提交 `309b6757584697f57e37889ff26b7858099ca66a`；用户已确认书面设计。本计划尚待确认，不能据此开始实现。

## Global Constraints

- 所有项目产物必须在 `E:/云服务工程/云服务项目-医疗云` 内。权威工作树为 `.worktrees/course-environment`，分支 `codex/course-environment`；本文文件路径均相对此树。
- 新生成文件仅用项目 `.local/tests`、`.local/builds`、`.local/runtime`、`.local/evidence`；短路径、独立 TEMP/TMP，finally 恢复环境，不共享 Maven target。
- 沿用 Java 8、Tomcat 9 Servlet/JSP 门户、WildFly 10 JAX-RS/EJB/JPA、MySQL；不引入新的服务或容器，不修改教师安装和端口3318/8180/8188/10090。
- 首管理员默认 HOSPITAL_ADMIN/ACTIVE；平台设置初始密码，服务器仅保存 BCrypt 哈希，成功页不回显。现有医生随机初始密码流程不变。
- 用户名 [A-Za-z0-9_]{3,64}，全局不区分 ASCII 大小写唯一；密码 8–72 UTF-8 字节，不裁剪、不归一化，拒绝控制字符/非法代理字符。
- operationKey 为非全零规范小写 UUID；两类创建共用 (actor_id, operation_key) 命名空间。无快速密码摘要，重试用原账号 BCrypt 验证；失败不保留密码输入。
- 账号状态仅 ACTIVE/DISABLED；最后启用管理员保护在冻结医院同样适用，控制面增补/启用不自动解冻医院。
- 005 新增 hospital_admin_operation：13 张应用数据库表、12 张非 audit_event 表；保留全部旧数据及严格旧升级器。
- 只由主代理暂存/提交/推送、操作真实服务/SQL/HTTP/故障。代理独占文件、仅虚构数据；运行凭据、正文、原始日志及摘要不上传 GitHub。
- 不向未结束临床/附件验收的共享环境升级005；不改冻结助手、重放消费编号或把旧未验证结果改为通过。本计划不批准另一个临床预检重构。
- 不做 PowerDesigner、新70台虚拟机、密码重置/找回、首次改密、真实通知、医院自助注册、角色迁移或其他业务扩展。

## Review Focus

1. 创建已提交但响应丢失：相同 key/原密码应恢复同一编号；误输新密码应冲突，不再建院（Task3/5/7）。
2. 用户名仅大小写不同，或与患者/医生并发抢名：只能有一个账户，失败开通不得留下医院（Task1/3/7）。
3. 两名平台操作者同时停用最后两管理员，或与冻结/资源写入交错：至少一人仍启用，锁后校验不能用旧快照（Task3/7/8）。
4. 历史医院使用非UUID编号、同名医院或分页越界：合法编号仍可维护，稳定分页不泄露其他角色（Task1/3/4/5）。
5. 代理返回HTML、超大错误体或带密码的异常文字：401/403仍权威，页面只显示本地白名单提示，不转发敏感文本（Task4/5/8）。

## 文件归属、执行顺序与验证约定

Task1冻结 contract → Task2（DDL）、Task3（业务）、Task5（门户）可并行；Task4等待Task3；Task6离线环境助手可准备，但真实部署等待Task2–5；Task7等待Task6；Task8等待Task7。每项独立规格/质量审查，修复找回原实现代理；每阶段主代理新鲜验证后精确提交。最后再做整分支审查，不因单项绿色合并。

共享实体注册/persistence.xml只归Task3；ApiFailureMapper只归Task4；platform.jsp/audit.jspf/CloudServiceFactory/PaasClient只归Task5；既有平台冻结服务及医院写入口只归Task3。不得多个代理同时改同一文件。根目录旧Spring/Vue树及未跟踪EAM保持原样。

实施开始前主代理在短 `.local/builds` 下建立固定提交的代码快照，核实源码导出换行/实际内容、路径、依赖及原生编译边界；不复制 target、凭据或运行库。下文 `mvn` 指已定位的 Maven，工作目录为该隔离快照；JAVA_HOME 指实际 JDK8，TEMP/TMP/java.io.tmpdir 都为本次项目内目录。默认构建清除无关 COURSE_/MEDICAL_/ATTACHMENT_/MINIO_/AWS_ 夹具及 JVM/Maven覆盖变量；真实专项仅注入该专项明确凭据，结束恢复。

共同命令模式：`mvn -o -f course/pom.xml -pl <module> -am -Dtest=<tests> -Dsurefire.failIfNoSpecifiedTests=false -Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final test`。新行为须先观察断言RED，再实现GREEN；缺工具、权限、依赖或夹具不计业务RED。全部验证保存实际退出码、精确执行/失败/错误/跳过数；默认可选MySQL跳过不能成为实机通过。

---

### Task 1：共享契约、纯校验与审计类型

**Files — Create:**
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalOpenRequest.java`
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalAdministratorCreateRequest.java`
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalAdminCreationView.java`
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalAdministratorView.java`
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalAdministratorPage.java`
- `course/contract/src/main/java/com/medcloud/course/contract/HospitalAdministrationRules.java`
- Test: `course/contract/src/test/java/com/medcloud/course/contract/HospitalAdministrationContractTest.java`

**Modify:** `course/contract/src/main/java/com/medcloud/course/contract/AuditEventType.java`、`AuditObjectType.java`（同目录）。复用现有 TenantStatusRequest，不重复另造状态请求。

**Interfaces:**
- HospitalOpenRequest：无参及 `(String hospitalName,String username,String initialPassword,String operationKey)` 构造，四字段get/set，拒绝未知字段；HospitalAdministratorCreateRequest对应后三字段。
- HospitalAdminCreationView `(String tenantId,String hospitalName,String administratorId,String username)`；HospitalAdministratorView `(String id,String tenantId,String username,String status)`；仅只读getters。
- HospitalAdministratorPage `(TenantView tenant,List<HospitalAdministratorView> items,long total,int page,int size,long activeAdministratorCount)`，精确同名getters，防御性复制列表；tenant承载Spec已要求的当前医院名称/编号/冻结提示，避免门户扫描医院全分页或信任URL名称。这是供本计划审阅的安全响应接口细化，不新增业务权限或新路由。
- Rules静态方法：`requireHospitalName(String)`、`requireUsername(String)`、`usernameKey(String)`、`requireInitialPassword(String)`、`requireOperationKey(String)`、`requireTargetId(String)`、`requireStatus(String)` 均返回String；`requirePage(String raw,int fallback,int max)`及`requireOffset(int page,int size)`返回int。无数据库依赖，非法输入抛无敏感值的IllegalArgumentException。
- 新事件 TENANT_OPEN→TENANT；HOSPITAL_ADMIN_CREATE/HOSPITAL_ADMIN_STATUS_CHANGE→HOSPITAL_ADMIN_ACCOUNT。

- [ ] **1. 写契约反例。** `passwordBytesAndUnicodeAreExact`断言8/72字节接受、7/73拒绝、空格不裁剪、NUL/换行/孤立代理字符拒绝；`opaqueLegacyIdAndCanonicalKey`接受`tenant-a`，拒绝空/超36/控制字符ID及全零/大写key；未知role/tenantId/id/passwordHash反序列化失败。
  ```java
  assertEquals("abc_1", HospitalAdministrationRules.usernameKey("AbC_1"));
  assertEquals("tenant-a", HospitalAdministrationRules.requireTargetId("tenant-a"));
  assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireInitialPassword("1234567"));
  assertFalse(new ObjectMapper().writeValueAsString(new HospitalAdminCreationView("t","院","a","user")).contains("password"));
  ```
- [ ] **2. 执行RED。** 共同命令module=`contract`、tests=`HospitalAdministrationContractTest`；记录缺失契约或新断言失败，不改旧测试迁就结果。
- [ ] **3. 实现上述类/枚举。** 名称原文先拒控制/非法代理、再仅裁两端U+0020；1–100 UTF-16单元。size默认20/最大100、page默认1，offset用long检查后转int。请求类不得生成含密码的toString。
- [ ] **4. 执行GREEN及既有契约回归。** tests=`HospitalAdministrationContractTest,ContractTest,AttachmentContractTest,ClinicalSupplementContractTest`；退出0、零failure/error，新测试无skip，JSON字段集合精确且无哈希/密码。
- [ ] **5. 独立审查并交主代理提交。** 冻结构造/方法签名和JSON字段，提交消息`feat(course): define hospital administration contracts`。

### Task 2：005保护迁移与初始化结构

**Create:** `course/deploy/migrations/005_hospital_onboarding.sql`、`course/deploy/scripts/upgrade-hospital-onboarding-database.ps1`、`course/deploy/tests/upgrade-hospital-onboarding-database.Tests.ps1`。**Modify:** `course/deploy/schema.sql`。不编辑003/004迁移器或已有证据。

**Interfaces:** 升级器参数沿用已部署004的 `MySqlCli,MySqlDumpCli,HostName,Port,Database,ExpectedServerUuid,BackupPath`；凭据仅MEDICAL_COURSE_DB_USER/PASSWORD进程环境。Database精确medical_cloud_course；精确004→005、完整005只读确认，其余拒绝。输出安全计数/状态，实际native退出立即测量。

- [ ] **1. 写原生边界行为测试。** 在本测试文件实现`Run-UpgradeCase([string]$Name)`的独立DDL/备份/identity替身，返回`DdlCalls,BackupCalls,TableCount,Unchanged`等非敏感结果。`WrongUuid`与`BackupFailed`都必须DdlCalls=0；`Exact004`产生13表，`Complete005Repeat`无DDL/二次备份；部分表/错误约束/异构基线拒绝且不清理。
  ```powershell
  Assert-Equal (Run-UpgradeCase 'WrongUuid').DdlCalls 0 'identity before DDL'
  Assert-Equal (Run-UpgradeCase 'Exact004').TableCount 13 'one new table'
  Assert-Equal (Run-UpgradeCase 'Complete005Repeat').DdlCalls 0 'repeat is read-only'
  ```
- [ ] **2. 在项目内TEMP运行该Tests.ps1观察RED。** 不启动MySQL；新005语义缺失应失败，不能只扫描脚本文本证明行为。
- [ ] **3. 实现精确结构。** 表字段、三外键、两个唯一约束及tenant外键索引完全按Spec§5；InnoDB、ID字符集与原表一致、key/type ASCII/ascii_bin。最新升级入口独立识别13表；身份/精确元数据/实际完整备份成功先于DDL；不做补偿DROP或覆盖恢复。
- [ ] **4. 重跑行为GREEN并独立计算索引预算。** 原生PS5退出0、零失败/skip；明确此非真实MySQL证明。实际新建/升级/JPA/教师5.6留Task6/7/8，不由此代理访问实例。
- [ ] **5. 独立审查并提交。** `feat(course): add protected hospital onboarding migration`，四文件精确提交。

### Task 3：开通业务、幂等与锁后身份复核

**Create（生产）：**
- `course/paas/src/main/java/com/medcloud/course/paas/tenant/HospitalAdminOperation.java`
- `course/paas/src/main/java/com/medcloud/course/paas/tenant/HospitalAdministrationException.java`
- `course/paas/src/main/java/com/medcloud/course/paas/tenant/PlatformAdministrationService.java`

**Modify（同tenant目录）：** `TenantAccessPolicy.java`、`PlatformTenantService.java`、`HospitalDepartmentService.java`、`HospitalDoctorService.java`；另改`course/paas/src/main/java/com/medcloud/course/paas/schedule/SlotAdminService.java`及main/test两份`META-INF/persistence.xml`。**Create tests：** `course/paas/src/test/java/com/medcloud/course/paas/HospitalAdministrationFixture.java`、`HospitalAdministrationServiceTest.java`、`HospitalAdministratorAuthorizationTest.java`（后两同目录）。不修改共享Account/Tenant基本结构。

**Interfaces（均准确类型）：**
- Service默认EJB构造与`PlatformAdministrationService(EntityManager)`、`PlatformAdministrationService(EntityManager,AuditWritePort)`测试构造。
- `HospitalAdminCreationView openHospital(CurrentActor,HospitalOpenRequest,AuditContext)`；`HospitalAdminCreationView addAdministrator(CurrentActor,String tenantId,HospitalAdministratorCreateRequest,AuditContext)`。
- `HospitalAdministratorPage administrators(CurrentActor,String tenantId,int page,int size)`；`HospitalAdministratorView changeAdministratorStatus(CurrentActor,String tenantId,String adminId,TenantStatusRequest,AuditContext)`。
- Exception `(int status,String code)`及getStatus/getCode；只允许Spec§7五个400/409代码，不附请求值。
- Policy新增`Account lockPlatformAccount(CurrentActor)`（排他刷新、ACTIVE/PLATFORM_ADMIN/tenant为空），`Tenant lockPlatformTenant(String)`（排他刷新、允许ACTIVE/DISABLED），`Account recheckHospitalWriter(CurrentActor,String tenantId)`（在调用者已持院锁后刷新、检查ACTIVE/角色/归属）。既有方法契约保持。
- Operation映射Spec§5九字段，protected无参JPA构造、只读getters；创建由Service填充，无实体HTTP输出。
- 新测试Fixture使用独立UUID的H2库，提供`<T>T inTx(Function<EntityManager,T>)`、`long count(Class<?>)`、`AuditContext audit(AuditEventType)`及`protected Map<String,Object> databaseProperties()`（默认独立H2，Task7覆写JDBC及validate）；声明CurrentActor常量PLATFORM/PLATFORM_OTHER（platform-1/platform-2，tenant为空），seed两院及所需管理员/其他角色，不向旧TenantFixture加入新行为。

- [ ] **1. 写业务/授权反例。** 原子一院一管理员、BCrypt校验且无明文落表；同key同输入同ID、不同密码/类型/医院/名称冲突；大小写抢名及同名医院；冻结/停用后重试不改状态；最后启用管理员、旧JWT/伪造租户、合法legacy ID/分页越界。用注入AuditWritePort在成功审计处抛异常，显式回滚后新事务验证tenant/account/operation均未新增。
  ```java
  HospitalAdminCreationView first = inTx(em -> new PlatformAdministrationService(em).openHospital(PLATFORM, request, audit(AuditEventType.TENANT_OPEN)));
  HospitalAdminCreationView retry = inTx(em -> new PlatformAdministrationService(em).openHospital(PLATFORM, request, audit(AuditEventType.TENANT_OPEN)));
  assertEquals(first.getAdministratorId(), retry.getAdministratorId());
  assertEquals(first.getTenantId(), retry.getTenantId());
  ```
- [ ] **2. 执行RED。** module=`paas`、tests=`HospitalAdministrationServiceTest,HospitalAdministratorAuthorizationTest`；采用真实EntityManager/JPA，不用纯mock代替幂等行/唯一约束/回滚。
- [ ] **3. 实现Service/entity/policy。** 统一平台actor→tenant→目标管理员锁序，锁后fresh验证；创建与op、SUCCESS审计同事务；重试校验op关联与BCrypt、不写密码/状态。最后管理员用锁后有限当前读而非旧COUNT；唯一冲突仅识别uk_course_username，不在rollback-only事务恢复为成功。
- [ ] **4. 补旧写入口与复核反例。** 冻结入口先用lockPlatformAccount；科室/医生/号源六个create/update入口在原Tenant共享锁后调用recheckHospitalWriter，再保持原资源锁序。参数化测试逐个验证等待前ACTIVE、获得锁后DISABLED会拒绝且无业务/成功审计写入；不得改医生密码生成/返回规则。
- [ ] **5. GREEN及回归。** 同focused测试加`PlatformTenantTest,TenantAccessPolicyTest,TenantWriteFreezeTest,HospitalResourceTest,HospitalSlotTest,AuditManagementTest`；退出0/零failure-error，新行为无skip。H2不能证明真实锁等待/独立JTA，转Task7/8。
- [ ] **6. 独立审查、主代理新鲜回归并提交。** `feat(course): implement atomic hospital administration`；Task4只在签名/结构冻结后接入，不写占位业务。

### Task 4：JAX-RS路由、异常和审计联调

**Modify:** `course/paas/src/main/java/com/medcloud/course/paas/api/PlatformTenantResource.java`、同目录`ApiFailureMapper.java`。**Create:** 同目录`HospitalAdministratorResource.java`；tests为`course/paas/src/test/java/com/medcloud/course/paas/HospitalAdministrationDispatchTest.java`、`HospitalAdministrationAuditTest.java`。必要既有`AuditDispatchTest.java`仅添加新事件回归，不另建未使用的路由注册表。

**Interfaces:** PlatformTenantResource新增`open(String authorization,HospitalOpenRequest)` POST根路径，保留既有构造并新增`(AuthService,PlatformTenantService,PlatformAdministrationService,AuditOperationRunner)`构造；HospitalAdministratorResource提供无参及`(AuthService,PlatformAdministrationService,AuditOperationRunner)`构造，`@Path("platform/tenants/{tenantId}/administrators")`，GET `list(String authorization,String tenantId,String rawPage,String rawSize)`、POST `create(String authorization,String tenantId,HospitalAdministratorCreateRequest)`、PUT `{adminId}/status` `changeStatus(String authorization,String tenantId,String adminId,TenantStatusRequest)`；返回ApiResponse包裹Task1精确DTO。

- [ ] **1. 写真实派发RED。** 参照现有`ClinicalSupplementDispatchTest`加载教师RESTEasy/Jackson provider，不造测试专用JSON reader/writer。检查四路径/四角色、401/403先于可绑定请求的业务校验、未知role等绑定拒绝、opaque ID、分页101/溢出、非法Json/415。匿名和其他角色不得触发新业务写调用。
- [ ] **2. 写审计与错误断言。** TENANT_OPEN成功只有一条、重试NO_CHANGE且不同requestId；失败原生异常/密码不进body，object_id为空；新五code映射400/409、reasonFor分别BAD_REQUEST/CONFLICT；X-Request-ID和响应requestId一致，成功实体无password/hash。
- [ ] **3. 执行RED后实现。** module=`paas`、tests=`HospitalAdministrationDispatchTest,HospitalAdministrationAuditTest`；变更方法全部@AuditTracked，以AuditOperationRunner包围认证及一次真实EJB业务调用，返回组装在事务调用成功之后。Mapper仅扩展新异常的固定公开消息，不放宽旧脱敏/状态映射。
- [ ] **4. GREEN/兼容验证。** focused加`PlatformTenantDispatchTest,AuditDispatchTest,AuditFailureScopeTest,ClinicalSupplementDispatchTest`；真实provider缺失是前置条件失败，不能跳过。旧构造和医院列表/冻结响应保持兼容。
- [ ] **5. 独立审查并提交。** `feat(course): expose hospital administration APIs`。

### Task 5：平台表单、管理员页与安全错误传递

**Create:** `course/portal/src/main/java/com/medcloud/course/portal/web/PlatformAdministrationServlet.java`、`PlatformAdministrationWorkspace.java`；`course/portal/src/main/webapp/WEB-INF/views/platform-hospital-open.jsp`、`platform-administrators.jsp`、`platform-administrator-create.jsp`、`platform-administration-result.jsp`。**Modify:** 既有`platform.jsp`、`audit.jspf`；`course/portal/src/main/java/com/medcloud/course/portal/factory/CloudServiceFactory.java`；web目录`PaasClient.java`、`PaasRejectedException.java`。**Create tests:** `course/portal/src/test/java/com/medcloud/course/portal/PlatformAdministrationPortalTest.java`、`PlatformAdministrationJspTest.java`、`PaasSafeErrorTest.java`；补既有`PlatformPortalTest.java`必要兼容断言。

**Interfaces:** Servlet无参与`PlatformAdministrationServlet(PaasClient)`构造；显式映射GET/POST `/platform/hospital/open`、GET `/platform/hospital/administrators`、GET/POST `/platform/hospital/administrator/create`、POST `/platform/hospital/administrator/status`、GET `/platform/hospital/result`，精确映射优先于已有PortalServlet的`/platform/*`。Workspace `(PaasClient)`，`void load(HttpServletRequest,String token)`、`HospitalAdminCreationView open(HttpServletRequest,String token)`、`HospitalAdminCreationView create(HttpServletRequest,String token)`、`void changeStatus(HttpServletRequest,String token)`；load按ServletPath加载各页非秘密数据。表单名固定hospitalName、username、initialPassword、confirmInitialPassword、operationKey、csrfToken及适用的tenantId/adminId/status/expectedStatus；确认值不传PaaS。

- [ ] **1. 写门户/客户端RED。** 多角色/无会话、重复参数/CSRF缺失不发PaaS写；无JS表单/label/键盘顺序/转义；成功303刷新GET；失败名称/用户名/key保持、密码和确认value为空/无session flash。丢失响应后同key原密码恢复，不能自动重POST/换key。分页与路径正确编码legacy ID。
  ```java
  assertEquals(303, successResponse.getStatus());
  assertFalse(renderedErrorHtml.contains(submittedPassword));
  assertEquals(originalOperationKey, errorRequest.getAttribute("operationKey"));
  ```
  以上响应/HTML来自本测试实际Servlet与JSP行为夹具，不以源码含某字符串冒充浏览器通过。
- [ ] **2. 明确安全错误扩展。** PaasRejectedException保留`(int,String)`构造，新增`(int,String,String code)`与`getCode()`，默认null。PaasClient JSON call仅对400/409读取最多现有1MiB错误体；只接受Spec五code与相符HTTP状态，并映射本地固定中文。401/403不依赖错误体仍优先；HTML/超大/未知code或敏感message均用原安全通用提示；二进制附件调用逻辑不改。
- [ ] **3. 执行RED。** module=`portal`、tests=`PlatformAdministrationPortalTest,PlatformAdministrationJspTest,PaasSafeErrorTest`；本地临时HTTP边界需权限时走正常审查，不以连接被拒当业务RED。
- [ ] **4. 实现新Servlet/Workspace/views。** 仅PLATFORM_ADMIN、现有accessToken/CSRF，UTF8/no-store；create表单server-side密码确认。错误forward只保留白名单非密码字段。成功仅将服务端核实的四字段HospitalAdminCreationView放进会话内最多10条的nonce→结果映射，以新随机UUID nonce 303到result?resultKey=nonce；映射不含密码/确认值/哈希/请求对象。GET先通过已有平台查询验证当前后端身份，再按本会话nonce读取，不信任URL名称/用户名，不调用业务POST。刷新保留同一结果，多标签各自nonce互不覆盖；未知/被淘汰结果显示已失效及平台列表入口，不重发操作。会话映射的读写与淘汰在短同步块内完成，不能持会话锁等待后端。添加刷新/两标签/第11条淘汰/跨会话nonce不可读/旧JWT反例。
- [ ] **5. 接平台入口与审计标签。** factory仅平台新增`open-hospital`、`manage-hospital-administrators`动作。列表activeAdministratorCount只是禁用按钮提示，后端仍独立判断。最后管理员冲突提示保留key/输入但要求复核，不隐式覆盖状态。其他角色和医生一次性密码页面保持。
- [ ] **6. GREEN及Portal全回归、独立审查并提交。** `feat(course): add hospital onboarding portal`；未知服务器错误文本不得直接输出，即使已HTML转义也不能显示密码。

### Task 6：新独立环境与真实构建预检

**Create:** `course/deploy/scripts/new-hospital-onboarding-fixture.ps1`、`course/deploy/tests/hospital-onboarding-fixture.Tests.ps1`。参考旧`new-attachments-fixture.ps1`的已验证启动模式，只导入无执行入口的动态ownership模块；不调用或点源旧创建器main，不复制其旧SDD证据路径/原生编码缺陷，不修改旧源码、固定6910库或消费记录。

**Interfaces:** 新入口参数`RuntimeRoot,MySqlBin,JavaHome,TomcatHome,WildFlyHome,MinioExecutable,MinioClientExecutable,SourceCommit,NewRunId,Execute`；默认退出4且无启动/SQL。内部变量统一Onboarding前缀；RuntimeRoot是项目.local/runtime内全新attachments-32hex目录，owner绑定NewRunId；代码快照位于短.local/builds，私有fixture/accounts/journal/logs仅在.local/runtime或.local/evidence。保存部署提交、实际schema版本及四组件身份，不改current指针。

- [ ] **1. 先写离线行为RED。** 错owner/PID/创建时间/监听/UUID、路径逃逸/reparse/不安全ACL禁止首写；导入后参数仍在；同ID/已有目录拒绝；创建意图先于副作用，身份丢失保留最后journal。源码导出换行、长路径、依赖、实际编译前置失败必须在服务启动/SQL之前停止，native状态不是旧LASTEXITCODE。真实文件/编排、只double外部边界。
- [ ] **2. 实现最小新启动入口并GREEN。** 新代码只复制必须配置到私有base，教师home只读；只loopback/新端口，校验每个监听owner和实际UUID。原生PS5 UTF8 stdin/相对ASCII私有MySQL optionfile，安全错误不转发native内容；TEMP/环境finally恢复。对象存储配置参考既有受审查私有桶/前缀策略，不开放匿名访问或复用旧桶。主代理才可启动/处理bootstrap密钥，任何新密钥不写公开命令文本/日志/报告；初始医院密码不参与bootstrap参数。
- [ ] **3. 主代理新鲜预检与独立审查。** 原生PS5运行该Tests.ps1需退出0；真正新代码ZIP/全部entry路径/UTF8内容、Java8编译及默认入口检查通过后，才申请正常实际操作审查。此为本批新入口，不修改尚待另行批准的临床预检助手。
- [ ] **4. 显式新RunId执行。** 创建独立MySQL/MinIO/双容器，精确004基线SQL取设计提交309b675的course/deploy/schema.sql，005新建/升级文件取已核实SourceCommit；两条路径在各自新库/实例验证，不通过删表或正则删DDL伪造004。部署同一SourceCommit两个WAR、生成虚构四角色及第二平台账号；SourceCommit为固定40hex提交，manifest写sourceCommit/schemaVersion=005。每次native/SQL/HTTP/证据前fresh身份核对；与旧环境隔离，不删除失败产物。
- [ ] **5. 核实恢复及交接。** 检查13表、JPA validate、四组件健康/身份、私有文件ACL及实际native退出；正向正常停止/启动的生命周期在新独立实例验证，最终给Task7明确running或stopped状态，不能看见监听就称产品成功。
- [ ] **6. 独立审查、主代理提交。** `test(course): prepare owned onboarding environment`。可单独拒绝此任务而不否定业务实现；未通过不得开始Task7实机。

### Task 7：真实MySQL专项与业务验收驱动

**Create:** `course/paas/src/test/java/com/medcloud/course/paas/MySqlHospitalAdministrationTest.java`、`course/deploy/scripts/verify-hospital-onboarding.ps1`、`course/deploy/tests/hospital-onboarding-acceptance.Tests.ps1`。消费Task6的动态owner/SourceCommit/schema005私有fixture，不兼容冒认旧12表。

**Interfaces:** verify参数`FixturePath,EvidenceRoot,NewRunId,Execute`，Onboarding前缀变量/别名。默认无SQL/login/claim，列必需项未验证并退出4；Execute必须新CreateNew消费记录。摘要scope=hospital-onboarding，required/executed/passed/failed/skipped/unverified/complete及具名门，任何必需missing/skip/false不可退出0。

- [ ] **1. 写强制MySQL测试。** 复用Task3 fixture的可配置`protected Map<String,Object> databaseProperties()`，读取前缀COURSE_ONBOARDING_MYSQL_下的REQUIRED/URL/USER/PASSWORD/UUID/OBSERVER_URL/OBSERVER_USER/OBSERVER_PASSWORD八项；REQUIRED=true而缺夹具必须fail，默认未配置才可选skip。MySQL必须validate，不使用create-drop或测试账号DDL；清理仅新专属测试库内已知表的DML，先子后父。每用例独立连接/新事务，真实锁观察需连接ID对应InnoDB等待，不能仅用sleep/TimeoutException。
  ```java
  assertEquals(1L, activeAdministratorsAfterBothTransactions);
  assertEquals(1L, committedOperationRowsForTheSameActorAndKey);
  assertEquals(firstTenantId, recoveredTenantId);
  ```
  固定32个必需案例：schemaJpaValidate(1)、sameKeyBothDirections(2)、caseInsensitiveGlobalUsernameRace（与PATIENT/DOCTOR各两提交方向，4）、disableLastTwoAdminsBothDirections(2)、disableVsEachHospitalWriterBothDirections（科室/医生/号源create/update六入口×两方向，12）、freezeVsAdminMaintenance（增补/启用/停用×两方向，6）、auditFailureRollsBackBusiness（开通/增补/启停各1，3）、replayFrozenAndDisabled(1)、zeroActiveRecovery(1)。使用显式具名参数标签，检查新事务账户/医院/操作/审计状态；运行前冻结完整case manifest及每个竞争案例的锁观察标签。新增案例可扩大清单，但不得为凑通过减少32项或改掉必需性。
- [ ] **2. 驱动离线TDD及审查。** 原生PS5针对真实编排/消费文件/精确trigger定义/意图前写入/所有写前身份/丢失后不覆写/安全错误/严格摘要写反例；double仅外部SQL/HTTP/进程。身份变更、触发器被替换、missing/duplicate/skip门均必须拒绝通过。默认入口和精确参数导入也实际测试。
- [ ] **3. 主代理执行真实MySQL。** 在Task6新owner内额外建立唯一测试库、每表最小DML及独立锁观察账号，运行`mvn -o -f course/pom.xml -Pmysql-integration -pl paas -am -Dtest=MySqlHospitalAdministrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。必须测native退出0、上述32例精确manifest全执行/0skip/0failure-error及全部具名等待。复核Task6实际迁移来源，补齐重复/异构/备份失败/部分DDL拒绝/旧升级器拒005，不能用替身代替真实边界。
- [ ] **4. 验收驱动覆盖真实业务API。** 至少包括四API/全角色、原子成功/幂等/冲突/冻结控制面/最后管理员/停用旧JWT，以及真实EJB成功审计注入故障后的12非审计表行级NULL/HEX比较（只在内存）、独立FAILED、相关锁释放、精确临时触发器恢复与健康后续。令牌在长检查后重新认证，不延长生产TTL/重试业务POST。初始密码、数据库密码、JWT不进argv/控制台，摘要无正文或行hash；残留/恢复未知不通过。
- [ ] **5. 独立审查、主代理复核并提交。** `test(course): verify hospital administration on owned MySQL`；root保存原失败/新运行独立证据，不修改旧日志，不以新成功倒写旧结果。教师5.6未实际执行就明确未验证，不偷偷换版本。

### Task 8：双容器浏览器、全回归及交付

**Create:** `docs/course-labs/2026-10-18-hospital-onboarding-acceptance.md`、`docs/AI-HANDOFF-2026-10-18-HOSPITAL-ONBOARDING.md`。**Modify:** `course/deploy/README.md`；`docs/course-labs/2026-10-04-full-scope-inventory.md`仅追加本批实际状态，不删除其他缺口。其他生产文件不归此任务，发现缺陷退回原owner。

- [ ] **1. 主代理执行真实浏览器验收。** Task7确认过的Task6新夹具：Chrome/Edge，正常与禁JS、键盘、中文/HTML转义、CSRF、错误后密码清空/非密码保留/同key重试、最后管理员提示与409、成功刷新不重复；停用账号退出/旧token拒绝、冻结医院增补/启用不解冻、历史opaque ID可操作。浏览器未连接就记未验证，不能以IAB或静态JSP替代。
- [ ] **2. 确认13表双容器与秘密边界。** 真正Tomcat页面→WildFly JAX-RS/EJB→JPA/MySQL；仅输出安全字段，管理员列表不查目标password_hash。扫描本轮私有日志/响应/HTML/session断言，测试秘密仅内存比对，确认新增initialPassword/confirmation字段无泄漏；不打印或上传秘密/哈希/临床摘要。完成正常恢复/需要时正常停止新测试实例并核实，无force kill。
- [ ] **3. 全构建。** 新短代码快照清除无关夹具环境，运行`mvn -o -f course/pom.xml -Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final clean verify`；实际退出0、全套零failure/error、新本地测试无skip。外部可选skip与已单独运行的强制结果逐项列出，不能合并成“全部实机通过”。
- [ ] **4. 按实测写交付文档。** README固定001→005顺序/旧严格工具保护/配置/操作步骤；验收报告逐门指向来源、明确未验证；交接列工作树、提交、私有证据位置、已消费编号/恢复状态和其余全系统缺口，无账户密码。示例只用虚构数据，四新API路径/字段与Task4一致。
- [ ] **5. 最终整分支独立审查。** 核对Spec§1–10、迁移与服务/JPA/schema一致、所有代理declined项逐一裁决；修复后重跑受影响验证，真实结果未闭合不能宣布本切片完成。
- [ ] **6. 主代理精确提交/推送及阶段汇报。** `docs(course): hand off hospital onboarding acceptance`；只推安全代码/文档，核实远端SHA。按已有最终合并授权，仅在整分支通过、目标树无冲突且保护其他脏改动时合并，否则报告条件未满足。此阶段不宣布全系统完成。

## 自查与审批交接

Spec覆盖：目标/边界→全局与Task1；架构→Task3/4/5；账号与密码/幂等→Task1/3/5/7；数据/保护升级→Task2/6/7；锁序→Task3/7/8；API/审计→Task1/4/7；门户→Task5/8；实测交付→Task6/7/8。Review Focus五项都已映射到具体反例。

实施方式沿用用户已选择的多智能体，并行仅按上述依赖及文件归属进行。计划确认前不派发实现、不改产品、不运行新夹具；用户确认本计划后，再使用subagent-driven-development按任务执行。当前临床预检调整及其旧失败运行仍是独立待决定事项。
