# Task 10 — 四角色业务页面实施报告

状态：DONE_WITH_CONCERNS

基线：08e17aa36fb2f0a5d2431b504152feaca2f1cfdf。
工作目录：E:/云服务工程/云服务项目-医疗云/.worktrees/complete-system。
实施开始时 git status --short 无输出，工作树干净。

## 交付

- 患者：CatalogView.vue（筛选、服务端分页、号源预约）、AppointmentsView.vue（本人预约和确认取消）、RecordsView.vue（已完成病历、处方、更正、授权附件）、OrdersView.vue（本人订单、确认模拟支付、服务端流水）。
- 医生：DoctorAppointmentsView.vue（本人预约、日期/状态筛选、今日预约、确认开始接诊）、ConsultationEditorView.vue（草稿、处方、先保存再完成、完成后只追加更正、附件上传）、DoctorRecordsView.vue（真实已完成预约及本会话已完成病历）。
- 医院：DepartmentsView.vue（医院资料、创建/更新科室）、DoctorsView.vue（创建医生、确认启停）、ScheduleSlotsView.vue（发布号源、本院可预约目录服务端分页）、HospitalStatisticsView.vue（日期范围统计）。
- 平台：TenantsView.vue（租户创建、确认启停、创建医院管理员、本次会话账号启停）、PlatformStatisticsView.vue（仅脱敏汇总）。
- DashboardView.vue：患者/医生使用各自列表计算指标，医院/平台使用相应角色统计 API；快捷入口复用 Task 9 菜单。
- router/index.ts：原 12 个业务占位路由逐一替换真实 lazy import，保留原 path、角色 meta、会话恢复和路由数量。接诊编辑器嵌入医生预约及记录页面。
- 新增共享组件 RecordDetails.vue、RecordFiles.vue、WorkflowFeedback.vue；useWorkflow.ts 统一请求状态、错误格式、金额和默认日期范围；consultationWorkspace.ts 在内存保存服务端返回的本人/本租户接诊结果，账号/租户/角色变化立即清空。
- style.css：补充限定在 .workspace 内的表单、表格、反馈、指标、附件样式，延续原青绿色视觉系统，没有修改应用壳或菜单。
- 四份指定页面测试和 workflowTestSupport.ts。所有请求使用真实 typed API 模块，测试仅在 HTTP 边界模拟服务器。

## RED / GREEN 证据与命令

以下测试均从上述工作目录运行；所有退出码和数量来自实际输出。

1. 编写四份页面测试后，首次运行：
   - 命令：npm --prefix frontend test -- --run src/views
   - 2026-09-22 15:37:06；退出 1。
   - 4 failed suites / 1 passed suite；原 Login 3 tests passed。
   - 四份测试分别因 CatalogView.vue、ConsultationEditorView.vue、DepartmentsView.vue、TenantsView.vue 尚不存在而 import 失败，符合 brief 指定缺失页面 RED。此时未创建生产页面。
2. 页面首轮实施后：
   - 命令：npm --prefix frontend test -- --run src/views
   - 15:52:29；退出 1；3 tests failed / 20 passed，2 failed suites / 3 passed。
   - 失败名称：
     - provides loading, empty and backend error states without cross-role requests
     - saves draft without creating an order and does not complete when saving fails
     - only appends a confirmed correction to a completed record
   - 原因：Vue mounted 状态尚未 nextTick 更新，以及脱离文档的 jsdom 按钮 click 不会触发表单默认提交。将测试分别改为 await nextTick 和触发表单 submit，保留真实组件行为与 API payload 断言。
3. 第一次全量验证：
   - 命令：npm --prefix frontend test -- --run
   - 15:54:27；退出 1；75 passed / 2 failed，9 passed suites / 1 failed。
   - registers the complete explicit role route matrix 和 lazy loads every authenticated page component 发现 platform/statistics 路由遗漏，且 platform/tenants 错指统计页。修复两条映射，未修改测试期望。
   - 命令：npm --prefix frontend run build
   - 同轮退出 0，vue-tsc 和 Vite 通过，144 modules transformed。
4. 扩展医院本院目录查询前先追加行为测试：
   - 命令：npm --prefix frontend test -- --run src/views/hospital/HospitalAdministration.spec.ts src/views/doctor/ConsultationEditorView.spec.ts
   - 15:56:40；退出 1；1 failed / 13 passed。
   - queries only this hospital available catalog slots with server pagination 因不存在目录请求而 RED，期望 tenantId=tenant-a、page=0、size=10，实际 undefined。
   - 同轮新增附件超过 10 MiB、内存记录所有权和退出清理回归断言通过。
   - 读取 SecurityConfiguration、CatalogController、ScheduleService，确认 catalog 可被已认证医院角色访问；实现固定当前 tenantId 查询和分页。
5. 页面 GREEN：
   - 命令：npm --prefix frontend test -- --run src/views
   - 15:57:43；退出 0；5 suites / 26 tests 全通过。
6. 最终全量验证：
   - 命令：npm --prefix frontend test -- --run
   - 15:59:02；退出 0；10 suites / 80 tests 全通过。
   - 原 Task 9：API 契约 36、角色菜单 4、共享组件 4、认证 store 3、路由 7、登录 3 均通过。
   - 新页面：患者 7、医生 9、医院 5、平台 2 均通过。
   - 命令：npm --prefix frontend run build
   - 退出 0；vue-tsc --noEmit 和 Vite 构建成功；146 modules transformed；主 JS 134.38 kB / gzip 52.68 kB。
   - 命令：git diff --check
   - 退出 0，无空白错误。Git 仅提示工作树 LF 将来可能转换为 CRLF。
   - 检索：rg -n 'axios|http\.(get|post|put|patch|delete)|ModulePlaceholder' frontend/src/views frontend/src/router/index.ts
   - 仅测试代码 axios-mock-adapter 命中；生产 Vue 无直接 Axios/HTTP 调用，路由无占位页引用。

## 自审

- 无后端、typed API 契约、角色菜单或认证恢复变更。
- 患者始终只消费 /api/patient 的列表/详情/预约/支付，以及从本人病历返回 ID 发起的授权附件接口；没有患者选择或伪造 patientId 的表单。
- 医生从本人预约 API 开始接诊；不使用 start 或 save 充当读取。内存病历显式过滤 doctorId 和 tenantId，不写入 localStorage/sessionStorage。
- 完成接诊必须填写主诉、诊断、建议，并验证每条处方；真实契约 complete 只接受 appointmentId，所以先 await saveConsultationDraft，再无 body 调用 completeConsultation。保存失败不执行完成；完成后隐藏编辑入口，只能追加确认过的更正。
- 预约取消、模拟支付、开始/完成接诊、更正、医院/科室/医生/账号状态变更和发布号源均有明确确认；请求执行中表单/操作禁用并显示反馈。
- 支付不生成客户端流水、不传交易号；仅展示服务器返回的 transactionNo 和 paidAt，已支付订单不再提供支付按钮。
- 上传选择前显示 PDF/JPG/PNG 和 10 MiB，accept 精确限制扩展名和 MIME；选择时校验类型、非空和字节上限，仍呈现后端拒绝信息。下载通过授权 API 返回签名 URL，并显示到期时间。
- 平台页面模板逐字段渲染汇总，不渲染完整响应，不导入病历/处方/附件展示组件；额外注入诊断和处方字段的 HTTP 响应测试证明不会展示这些内容。
- 所有新表单均使用 label，支持加载、空状态、中文错误和操作反馈；列表的请求边界以真实服务契约为准，未对数组接口伪造服务端分页。
- 已自审全部新增文件及路由 diff；未运行浏览器真人交互、Docker 全链路或后端测试，本 Task 的验证范围是 Vue HTTP 边界交互、既有前端契约和生产编译。

## 顾虑 / 经 controller 确认的契约限制

1. 当前 API 没有医生 GET 接诊/历史病历。开始、保存、完成返回的记录可以在本浏览器会话内继续编辑/查看/追加更正；刷新后服务端草稿仍存在，但前端无法安全恢复。历史页明确区分真实已完成预约与本会话记录，不伪造持久历史。
2. 医院接口没有完整号源历史 GET/修改/停用。页面实现真实发布，并通过已获授权的公开目录限定本院 tenantId 查询当前可预约号源；完整历史与修改明确标注不可用。
3. 平台接口没有医院管理员账号列表/读取。页面展示真实租户，并允许创建管理员和启停本会话创建返回的账号；不假装恢复完整账号列表。
4. 除号源目录外现有列表均返回数组，未提供 page/size。仅目录使用服务端分页，其余展示接口返回列表，不扩后端、不猜接口。
5. 设计中的医院/科室目录选择 API 未在 Task 9 提供，患者目录直接显示服务端返回名称，可按医院/科室编号及日期筛选。
6. 现有订单 API 不提供取消待支付订单；页面不编造该操作。

以上限制已在实施期间告知 controller，并获准按最小安全实现继续；最终状态据此为 DONE_WITH_CONCERNS。

## Fix round 1：补齐设计要求的持久化读取与管理接口

### RED

- 后端新增 `Task10PersistentReadApiTest`，覆盖：启用医院/科室目录、医生本人本租户病历恢复、医院本租户完整排班历史与停用、平台持久医院管理员列表及敏感字段缺失。
- 命令：`mvn -f backend/pom.xml -Dtest=Task10PersistentReadApiTest test`
- 结果：4 tests，4 failures；四组目标路由均不存在并返回 500，认证和数据库夹具正常。
- 前端扩展 typed API 契约和四角色页面测试。
- 命令：`npm --prefix frontend test -- --run src/api/business.spec.ts src/views/patient/PatientWorkflow.spec.ts src/views/doctor/ConsultationEditorView.spec.ts src/views/hospital/HospitalAdministration.spec.ts src/views/platform/PlatformAdministration.spec.ts`
- 结果：5 files failed；66 tests 中 11 failed、55 passed。失败均为新增 API 函数或页面持久化交互缺失。

### GREEN 与调试记录

- 后端首次 GREEN 为 3/4；医生记录查询因测试夹具写入不存在的 `DRAFT` 枚举而返回 500。领域状态实际为 `IN_PROGRESS|COMPLETED`；修正夹具为 `IN_PROGRESS` 后 4/4 通过，未修改生产查询以掩盖错误。
- 后端受影响回归：`Task10PersistentReadApiTest,ScheduleCatalogTest,HospitalScheduleTest,ConsultationWorkflowTest,PlatformTenantManagementTest,RoleAuthorizationTest`，51/51 通过。
- 前端相关 GREEN：5 files、66/66 通过。
- 全量验证时发现系统 `JAVA_HOME` 指向 JDK 8，而命令行 `java` 指向 JDK 21，导致首次 Maven 全量运行在执行测试前发生 class version 65/52 错误；使用已安装的 `D:\Java\jdk-21.0.11` 仅覆盖本次命令环境后重跑。
- 最终后端：`$env:JAVA_HOME='D:\Java\jdk-21.0.11'; mvn -f backend/pom.xml test`，176/176 通过。
- 最终前端：`npm --prefix frontend test -- --run`，10 files、87/87 通过。
- 生产构建：`npm --prefix frontend run build`，146 modules transformed，exit 0。
- `git diff --check`：exit 0；仅有 Git 的 LF→CRLF 工作区提示。

### 实现结果

- 增加认证后目录：`GET /api/catalog/tenants` 与 `GET /api/catalog/tenants/{tenantId}/departments`，只返回启用数据；患者改用医院/科室下拉选择。
- 增加医生病历列表与详情读取，仓储查询同时绑定 JWT 医生 ID 和租户 ID；预约页可恢复草稿，历史页可恢复已完成病历和更正入口。
- 增加医院排班完整历史 GET 与状态 PUT；只按认证租户读取/修改，不提供删除路径，已有预约号源通过停用保留历史。
- 增加平台按租户读取医院管理员列表；响应只有账号、角色、状态和租户字段，不含任何病历、处方或附件数据。

### 顾虑

- 平台前端按医院逐个读取管理员列表，演示数据量下清晰可靠，但租户数量较大时会形成 1+N 请求；留给最终审查决定是否需要聚合接口。
- 本机 Maven 默认仍由系统 `JAVA_HOME` 选择 JDK 8；项目要求 Java 21，后续 Compose/README 应明确 Java 21 或使用容器构建，避免本机环境漂移。
