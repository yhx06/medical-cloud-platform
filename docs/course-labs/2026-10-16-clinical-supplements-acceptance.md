# 病情补充、私有草稿与病历更正：分层验收指南

记录日期：2026-10-16；Fix1 状态补充：2026-10-17。课程实现已接入，Task6 完整验收尚未完成。临床驱动首审三项 Important 已修正，独立窄复审结论为 3 ADDRESSED、无新增 Critical/Important；驱动及行为测试精确提交 `2b9c835`。根代理本轮真实 HTTP 已开始，结果待定，不能写成通过或 `wholeTask6Accepted=true`。

本指南与范围清单初稿已独立审查批准并提交 `6dfe688`；本次 Fix1 状态/权限契约勘误另待原审查者窄复审。历史首审、失败及后续修正保留，实际结果由根代理追加；驱动级批准不等于 Task6 或全系统验收批准。

范围依据为[已批准设计](../superpowers/specs/2026-10-16-medical-cloud-clinical-supplements-design.md)及[实施计划](../superpowers/plans/2026-10-16-medical-cloud-clinical-supplements.md)。完整系统剩余范围见[范围清单](2026-10-04-full-scope-inventory.md)，附件状态另见[附件专项](2026-10-12-attachments-acceptance.md)。本切片补充 FR-APT-05、FR-CON-05 和此前约定的接诊草稿，不包含处方、订单取消、机构账号开通、AI/评价或真实医疗支付。

## 1. 课程技术路线与业务边界

沿用 Java 8、Tomcat 9 Servlet/JSP、WildFly 10 JAX-RS/EJB/JPA/JNDI、MySQL 的双 WAR 路线：`portal.war` 调用 `paas.war`，生产持久化使用 `validate`，不能自动建表。实际数据源必须是 `java:/jdbc/MedicalCloudCourseDS`，持久化单元 `medicalCloudCourse`，事务隔离要求 `REPEATABLE_READ`；两个 JVM/JDBC 使用 `Asia/Shanghai`。仅有配置文件、H2 或 RESOURCE_LOCAL 测试不能证明实际 WildFly JTA 绑定及事务边界。

患者自述与医生四字段分别保存，不自动覆盖。患者本人可在 BOOKED/IN_PROGRESS 补充或清空自述；COMPLETED/CANCELED 不可写。私有草稿仅原分配医生本人本院可读写，保存仅限 IN_PROGRESS，不生成订单。完成请求提交本次完整四字段并核对草稿版本，同事务生成冻结原始病历/唯一订单并删除草稿；没有草稿时保留旧 complete 兼容。

更正只追加，保留前后四字段、原因、医生与时间；原始病历及订单保持不变。`expectedRevision` 控制并发，规范 UUID `operationKey` 控制重试；同键同请求在后续更正出现后仍恢复原历史项，变体复用返回 409。原始、当前有效和私有草稿是三个不同来源。患者可读本人完整更正历史，每页默认/最多 **5** 条，不能用截断列表冒充完整历史。医院及平台管理员只看脱敏审计，不能读取临床正文。

患者自述最多 500 个 Unicode 码点；医生主诉/诊断各 500、意见/备注及更正原因各 2000；拒绝 NUL 和非法代理字符。临床文字、原因、令牌与密码不得进入一般审计、容器日志、URL、长期 session、截图或公开证据。所有演示数据均为虚构。

## 2. 部署依赖与 004 顺序

新建空库使用最新 [schema.sql](../../course/deploy/schema.sql)。已有规范课程库按已核对结构依次完成 `001_p0_completion.sql → 002_audit_events.sql → 003_attachments.sql → 004_clinical_supplements.sql`，不得重跑种子或用 `CREATE TABLE IF NOT EXISTS` 代替升级。004 的唯一旧基线是已完成 003 的精确十表结构；升级后为十二表，增加预约三列及 `clinical_draft`、`record_correction`。

使用[临床保护升级器](../../course/deploy/scripts/upgrade-clinical-database.ps1)，显式提供 `-MySqlCli`、`-MySqlDumpCli`、`-HostName`、`-Port`、`-Database`、`-ExpectedServerUuid`、`-BackupPath`；凭据按既有私有环境机制提供，不放命令参数。它只接受规范库名 `medical_cloud_course`，核对真实端口/UUID及完整结构，在全库备份成功后才执行 [004](../../course/deploy/migrations/004_clinical_supplements.sql)。完整正确的新结构只读退出；部分、外来、同名异构或备份失败均应拒绝。DDL 可能部分提交，失败时保留实际结构/备份，不自动 repair、DROP 或覆盖恢复。

**004 后用最新临床升级器核验，不能再次运行旧 003 升级器。** 003 的严格旧预约形状检查会拒绝新增列，不应为重跑而放宽。迁移工具不自动部署 WAR。

只在新的专属 disposable 数据库和双容器副本验证。保护端口 `3318/8180/8188/10090`、旧附件故障窗口、教师安装、需求/EAM/备份和历史证据不得被本切片部署覆盖。先结束互斥故障窗口，再核对新实例的归属、数据源、JDBC 驱动及 WAR 部署完成状态；仅复制 WAR 不是部署成功证明。构建/部署细节参见[部署说明](../../course/deploy/README.md)，其中历史实例示例不能作为本临床夹具的目标授权。

MinIO 是既有附件依赖；补充/草稿/更正事务设计上不依赖对象存储，但“停 MinIO 后仍可运行”的临床门必须在当前切片实际验证。以前的预约→支付核心链停存储观察不能自动替代本门。

## 3. 当前证据分层

| 层级 | 已有记录 | 不可外推的结论 |
| --- | --- | --- |
| 实现 | 契约/内容规则、保护迁移、实体/服务、API/审计、Portal 已实现；原始/有效/草稿分离 | 不等于当前双容器、浏览器或全系统验收完成 |
| 驱动行为 | 历史初轮 PS5.1 为 21/174；Fix1 新增反例后为 **40 场景、303 断言、0 失败**，根代理新鲜复跑 exit 0；三项 Important 窄复审已解决，精确提交 `2b9c835` | AST 边界测试及驱动 scoped 批准不是实际 HTTP、数据库、JTA、浏览器或 wholeTask6 通过 |
| 真实迁移 | 根代理新专属 MySQL 8.0.45 六项观察：十表基线、全备份、旧病历/已付订单逐行摘要不变、旧预约 NULL/NULL/0、只读重复、空库/升级后元数据一致 | 仅这六项；真实部分 DDL/备份失败/外来拒绝等仍需补齐，不能填满迁移矩阵 |
| 真实业务 SQL | 根代理 mandatory MySQL 专项 9 项执行、0 失败/错误/跳过；实际锁观察及 RESOURCE_LOCAL 审计故障回滚有记录 | 不是 EJB/JTA、双容器 API 授权/审计故障或完整临床验收 |
| 当前实机准备/运行 | 根代理已用真实 API 准备主流程、旧 complete、取消、浏览器四个独立未来预约；私有账号 ACL 已核对；Fix1 批准后已开始真实 HTTP 驱动 | 截至本次勘误，真实运行结果待定；准备或启动不是成功，本节没有实机通过声明 |
| 浏览器/故障 | 当前临床 Chrome/Edge、禁 JS、键盘、CSRF/转义、医院冻结竞态、停止 MinIO 及 WildFly JTA/独立失败审计门仍未验 | 旧审计浏览器/旧附件观察不能迁移为当前临床门通过 |

本地原始证据在 Git 忽略目录 `.superpowers/sdd/2026-10-16-medical-cloud-clinical-supplements/`，主要为 `task-2-report.md` 的根代理实机增量、`task-6-mysql-report.md` 的 mandatory 九项增量、`task-6-driver-report.md` 和 RED/GREEN 日志。它们不随公开文档携带；换机需由运行负责人提供脱敏证据。初次失败、后续修正与可选跳过均保留，不追溯改写。

## 4. 三参数真实 HTTP 驱动

[驱动](../../course/deploy/scripts/verify-clinical-supplements.ps1)的 Fix1 已获 scoped 窄复审批准并提交，是增量验收入口，仍只有三个必需参数：`-FixturePath`、`-EvidenceRoot`、`-AccountsPath`。下面是待替换占位值的调用格式，不会替用户选择目标：

```powershell
powershell.exe -NoProfile -File .\course\deploy\scripts\verify-clinical-supplements.ps1 `
    -FixturePath 'E:\<本轮SDD路径>\clinical-running-20261016-fixture.json' `
    -EvidenceRoot 'E:\<本轮owned运行根>\clinical-application\clinical-evidence' `
    -AccountsPath 'E:\<本轮owned运行根>\clinical-application\private-accounts.json'
$clinicalVerificationExit = $LASTEXITCODE
```

当前新运行身份是附件 SDD `fixtures/6910fd2545ca4cf6ae88e94fb1203785/clinical-running-20261016-fixture.json`，这是根代理准备的当前清单；同目录旧 `fixture.json` 的两个容器 PID 已过期。描述文件不证明服务仍在运行。每次实际请求及证据目录/文件写入前，驱动均重新核对清单内容和共享 MySQL/PaaS 归属：owner marker、PID/创建时间、可执行文件/运行目录、保护端口、监听、静态及实时 MySQL UUID/端口/datadir。读取容器日志还检查 Portal 归属。调用不能改成任意 URL，也没有跳过、强制通过、导入历史结果或生产 mock 参数。

描述文件允许位于既有 SDD 目录，但必须是绝对路径、普通文件、大小不超过 64 KiB、所有祖先无 reparse，并在运行中保持内容不变。`AccountsPath` 和 `EvidenceRoot` 必须是 `fixture.runtimeRoot` 内的绝对后代路径，所有祖先无 reparse，并同处一个受保护的私有目录边界。运行根本身可以是公开目录，例如只将其 `clinical-application` 子目录设为私有；更深层目录可继承该私有边界，不要求把整个 runtime 改权限。

私有边界及边界内所有现有祖先、目录和目标的 ACL 仅允许当前用户、SYSTEM、Administrators，所有者也须属于这些主体。账号文件仍必须是最多 64 KiB 的普通文件且 ACL **显式保护**。每次 HTTP 请求、每次证据目录/文件写入前重新核对账号、证据祖先和现有目标权限；UUID 子目录及新文件的继承 ACL 同样检查，不能只在预检时看一次。summary 以 CreateNew 独占打开，再核对归属及新文件权限后写入。运行中权限变宽会阻止下一次请求或写证据。证据父目录下每次新建 UUID 子目录，不能覆盖本轮或历史结果。

账号 JSON 契约如下；**不要把真实文件内容贴入文档、命令行、聊天或 Git**：

| 字段 | 要求 |
| --- | --- |
| `ownerId` | 与夹具完全一致 |
| `appointmentId` | 根代理新准备的本人患者/原医生 BOOKED 预约；无病情修改、草稿、正式病历或订单 |
| `legacyAppointmentId` | 可选，单独的新 BOOKED 预约，用于旧无草稿 complete；缺失时该必需门仍未验证 |
| `canceledAppointmentId` | 可选，单独同患者/原医生 CANCELED 预约，用于拒绝写；缺失时该必需门仍未验证 |
| 七个账号对象 | `patient`、`otherPatient`、`doctor`、`otherDoctor`、`crossDoctor`、`hospital`、`platform`，各含 `username/password/id/tenantId` |

全部账号 ID、非空 tenantId 及预约 ID 使用规范小写 UUID；账号 ID/用户名各不重复。用户名匹配 `^[A-Za-z0-9_.-]{1,100}$`，密码非空且最多 1000 字符。doctor/otherDoctor/hospital 为同医院，crossDoctor 属于另一医院；患者/平台租户字段可为 null。登录返回的 username/role 必须分别匹配 PATIENT、DOCTOR、HOSPITAL_ADMIN、PLATFORM_ADMIN；登录 DTO 不返回 id/tenantId，不能虚构字段或解码 JWT 当作数据库归属证明。浏览器另留的预约不由这三个参数驱动自动消耗。

脚本会实际修改显式提供的新虚构预约：补充、开始、保存、完成、追加六次更正及幂等恢复。每次新运行必须由根代理准备新的完整初始目标，不能重跑已完成目标并称作新的 BOOKED 场景，不能选择种子历史预约或随意替换对象。缺少必需前置会失败或保持未验证，不会自动创建/猜测账号和资源。原始正文、订单逐字段比较只留内存，HTTP 固定本机 `/paas/api` 路径，禁止重定向/代理，UTF-8 请求响应上限 1 MiB，单次超时 15 秒。

## 5. 53 个必需门与摘要解释

当前 Fix1 注册表仍有 **53 门**；以下分类仅用于阅读，不改变门名或执行要求：

| 分组 | 门数 | 当前驱动的能力/限制 |
| --- | --- | --- |
| 夹具/身份/新目标、病情版本、私有草稿、401/403、旧/新完成、更正/重试、原始/订单不变、历史分页、只读状态、安全错误/审计/容器日志 | 23 | 可执行增量链；旧完成/取消目标及完整可读日志集合缺失时各自未验证 |
| 空库/十表升级/重复只读/部分拒绝/外来拒绝/备份失败拒绝/旧行保留 | 7 | 尚未接入驱动实际执行；独立迁移记录不能自动导入为 passed |
| 真实 MySQL 草稿竞争、完成/草稿、完成/补充、同版本更正 | 4 | 尚未接入驱动；独立九项 RESOURCE_LOCAL 记录不自动填入 |
| 冻结/写竞态、停用账号、冻结读写、四状态、唯一病历/订单 | 5 | 需完整实机矩阵；局部 HTTP 观察不填满整门 |
| 成功审计回滚、独立失败审计、WildFly EJB/JTA | 3 | 未验；不能用 RESOURCE_LOCAL 注入替代容器事务 |
| 双容器实际绑定、停 MinIO 临床独立性 | 2 | 未验；配置或旧核心链不是本切片证明 |
| Chrome、Edge、禁 JS、键盘、CSRF、中文/转义、冲突输入保留 | 7 | 未验；真实 JSP 渲染测试不等于浏览器操作 |
| 全量 clean verify、独立全切片审查 | 2 | Task6 尚未关闭；本驱动 scoped 审查也不能代替全切片审查 |

输出为 `<EvidenceRoot>/<本轮UUID>/clinical-supplements-summary.json`。它包含固定门名数组 `required/executed/passed/failed/skipped/unverified`、对应计数、请求数，以及 `completeDriverRun`、`wholeTask6Accepted` 和少量比较结果布尔值。`executed = passed + failed`；每个必需门必须落在 passed/failed/skipped/unverified 之一。正文、原因、密码/令牌、完整响应、内容摘要和原始异常不写入摘要。

测试边界里的完整 HTTP 绿色基线是 **21 passed / 32 unverified / 0 failed**，退出 **4**，`completeDriverRun=true` 但 `wholeTask6Accepted=false`；这只是行为契约测试，不是实际环境结果。提供独立旧完成/取消目标可以分别推进其门，不会清除其他未验门。实际计数必须读取根代理本轮唯一摘要，不能照抄这个测试数字。

| 退出码 | 含义 |
| --- | --- |
| 1 | 已观察到失败门 |
| 3 | 归属或证据前置中止；无有效最终摘要，不能算通过 |
| 4 | 增量驱动运行结束，但必需门仍未验证 |
| 0 | 必需门全部 passed 且最终摘要已写入，当前增量实现尚不能达到此条件 |

任何必需门缺失、failed、skipped 或 unverified 均不能成功。没有最终摘要或中断运行不能验收；归属丢失后连失败摘要也禁止写。驱动没有历史 JSON/日志导入接口，不允许手工把独立报告转成 passed、缩小必需表、把默认可选 skip 当通过或用测试替身填实机门。

容器脱敏门需要 WildFly `log/server.log`、非空 Portal `logs/` 及运行根四个 launcher captures：`paas.out.log/paas.err.log/portal.out.log/portal.err.log`；还扫描两日志目录全部文件及本轮根目录额外 paas/portal `.log` captures。缺少整套可读位置不叫干净；当前最多 100 文件、单文件 16 MiB，超过范围、读取失败或发现 canary 均不能通过。

## 6. 继续验收与交付

根代理已完成本次驱动 scoped 审查、Fix1 与窄复审，并已在新 owned 环境开始真实链；本次文档勘误不预判运行结果，仍须保留每次失败和本轮独立摘要。后续驱动修复也须先复审再实机运行。迁移失败保护、锁观察、医院冻结竞态和故障注入串行协调，不能同时占用旧附件故障窗口。审计故障必须证明真实成功事务整笔回滚、失败审计在业务事务结束后独立记录，以及恢复后可继续；不要只看 HTTP 500。

真实浏览器仍须分别在 Chrome/Edge、正常/禁 JS 下操作患者补充、医生保存/重开/完成/更正和患者历史，观察 401/403/409/冻结、中文/转义、原生表单、键盘和陈旧输入保留。工具不能连接或某步骤未完成时逐项未验，不能用 DOM/HTTP/JSP 测试顶替。先前审计无 JS GET 或附件列表观察只证明其原范围。

最后执行当前源全量 `clean verify` 并披露所有可选跳过，完成独立全切片审查及修复；通过每个必需门后才汇报 Task6 阶段完成。推送/合并前另核整分支审查与目标树保护。停止服务仅对本轮精确 owned 进程正常关闭，保留数据、备份、失败和成功证据。MySQL 8 实测与教师 5.6 未验分别披露；本机 loopback 不代表云端部署。

本阶段不宣告全项目完成。跨号源时段冲突、订单取消规则、医院/管理员开通、完整资料/支付流水号/其他列表分页、性能与恢复、云端、最终报告/PPT/演示和真实成员贡献继续保留；处方范围冲突与 P2 可选 AI/评价仍按原清单，不默默删除或纳入。70 台服务器仅模型，不再新增 PowerDesigner 图。

## 7. 2026-10-17 Fix1 审查增量

历史首审三项 Important 分别为证据私有祖先/目标 ACL 缺口、JSON 转义文本绕过原始比较、更正及中间历史正文核对不足。Fix1 新增反例并修复为逐次私有边界检查、递归 decoded string leaf 脱敏检查、由本次请求建立内存期望并逐条核对六条持久化历史；原失败记录保留。最终 PS5.1 行为测试 40/303/0，根代理独立复跑及源码 SHA 匹配，原审查者窄复审 3 ADDRESSED，无新增 Critical/Important，精确两文件提交 `2b9c835`。三参数、53 门和未验非零规则未变；这项审查批准仅覆盖驱动修复，不是本轮真实 HTTP 结果或 wholeTask6 通过。
