# 医疗云：下一位 AI 接续入口（2026-10-19）

本文是当前接续入口；旧交接、实验记录保留为历史证据，不追溯改写。**系统未全部完成，医院开通这一批也未完成整体验收。** 用户最新要求：当前小任务收尾后停止扩展，将本批余项及整个系统后续工作交给下一位 AI；其完成后，由原会话进行 review。

先读本文，再按 [后续全部任务清单](DEVELOPMENT-BACKLOG-2026-10-19.md)执行。不要从历史交接重新实现已经存在的附件、病情补充、草稿或更正业务。

## 1. 唯一工作位置和技术路线

本机项目根：`E:/云服务工程/云服务项目-医疗云`。

课程权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，分支 `codex/course-environment`。顶层 `backend/`、`frontend/` 是旧 Java21/Spring/Vue 路线，不在那里开发课程版。从 Git 新克隆时，仓库中含 `course/` 的对应课程分支根就是源码根；不要机械创建重复的 `.worktrees/course-environment` 嵌套。

| 层 | 当前课程实现 |
| --- | --- |
| 共享契约 | `course/contract`：DTO、角色、业务校验和审计枚举 |
| SaaS 门户 | `course/portal`：Java8、Tomcat9、Servlet/JSP、原生表单和服务调用 |
| PaaS | `course/paas`：WildFly10、JAX-RS/RESTEasy、EJB/JTA、JPA/Hibernate、JNDI MySQL 数据源 |
| 私有附件 SDK | `course/attachment-sdk`：构建期隔离依赖，不是第三个部署服务 |
| 数据与存储 | MySQL；MinIO 私有桶/前缀，仅虚构课程数据 |
| 部署及验证 | `course/deploy`：SQL、保护升级器、PowerShell5.1 脚本、专项测试 |

教师兼容目标仍是 Java8、Tomcat9、WildFly10。真实验收使用过 MySQL8.0.45，**不等于教师 MySQL5.6 已验**。不要直接改回 Spring/Vue、升级教师安装或引入新的服务。

## 2. 接手前必须遵守

- 先读工作树 `AGENTS.md`。项目新产物只放项目根 `.local/tests`、`.local/builds`、`.local/runtime`、`.local/evidence`；为测试设置独立 TEMP/TMP/java.io.tmpdir，finally 恢复。不要在 E 盘根、C 盘用户目录或系统临时目录重新散落项目文件。
- 保留其他工作树及用户改动、需求、备份、历史失败记录和未跟踪的 `docs/course-architecture/Medical Cloud API Probe.eam`。不做广泛清理、不硬重置、不删除失败数据。
- 只有协调主代理操作共享 Git index、真实服务、SQL、实际 API、故障注入及凭据；子代理独占文件和构建目录。真实环境变更串行，不要临床/附件/医院验收并行写同一库。
- 保护教师 D 盘安装和端口3318/8180/8188/10090。不再制作 PowerDesigner 图；70台服务器只是历史建模，不能启动70台服务器。
- 凭据、JWT、数据库、临床正文、原始日志、行级快照及其摘要不上传 Git。不要在聊天、命令参数、报告里输出密码。不要把历史 README 的演示口令当作当前私有环境口令。
- Git 已配置本机代理 `http://127.0.0.1:7890`；不要重复修改全局配置。正常审批网络中断表示动作未执行，不是允许绕过审批；不得关闭 TLS 或 force push。
- 后续新业务尚未批准的规则先设计并让用户确认；本文不是对处方、订单取消、AI、云资源等范围的自动授权。

## 3. 已保存的当前小任务

医院开通书面设计、实施计划均已经用户确认，不要再次要求从零审批：

- [已确认设计](superpowers/specs/2026-10-18-medical-cloud-hospital-onboarding-design.md)
- [已确认八任务计划](superpowers/plans/2026-10-18-medical-cloud-hospital-onboarding.md)

本轮最后一个已审查功能提交：`75f97c43712b9ac1dd89aadb0b6339bf2e6cc0ad`。文档和明确标记的 WIP 可能在此之后另有提交，接手时用 `git log`、`git status` 核对，不把本文写入自己的提交号造成循环引用。

| 提交 | 内容与证据边界 |
| --- | --- |
| `975e8f3` / `f172b5d` | 已批准附件设计/计划，历史切片，不能据此认定附件验收完成 |
| `a2b239b` | 已批准诊疗增强计划，当前不是从零开发阶段 |
| `309b675` / `d88ff30` | 医院开通设计/计划；309b675 的 schema 是精确004历史基线 |
| `0ffa19a` | 医院开通契约；独立审查通过 |
| `dc52840` | 005保护迁移及不可变004测试基线；离线36+9项通过，未在本批新环境实际升级 |
| `eeacc89` | 原子医院/管理员业务、停用/锁保护；独立审查、主代理全构建检查通过；不等于真实 MySQL/JTA 门通过 |
| `252c5e5` | 四个医院管理 API、错误映射、审计；独立审查通过，主代理六类96测试通过，0失败/错误/跳过 |
| `75f97c4` | 医院开通/管理员门户；严格错误解析修复经定向复审通过；整合仍有旧附件失败，见下一节 |
| `57a7c8f` | 三个未审查验收WIP文件的交接保存点，不是完成提交，不可直接Execute |

接口相对 `/paas/api`：

1. `POST /platform/tenants`：医院＋首管理员原子开通。
2. `GET /platform/tenants/{tenantId}/administrators`：管理员分页、安全投影及启用人数。
3. `POST /platform/tenants/{tenantId}/administrators`：增补管理员。
4. `PUT /platform/tenants/{tenantId}/administrators/{adminId}/status`：启停管理员。

本批关键规则不可回退：仅平台管理员操作；平台设置初始密码，服务器仅 BCrypt，不在结果页回显；用户名全局 ASCII 大小写不敏感；创建共享 `(actor_id, operation_key)` 幂等空间，重试用原密码的 BCrypt 验证而非快速摘要；冻结医院仍能维护账号，但不能停用最后一个 ACTIVE 管理员，也不自动解冻医院。医院写入等待锁之后重新核验数据库中的账号状态/归属，不能只相信旧 JWT。

幂等查询依赖当前 API 的实际事务路径：`AuditOperationRunner` NOT_SUPPORTED → 单独 AuthService EJB → 一个业务 EJB，业务先锁平台账号、后作首个一致性查询。审查认为源码路径相符，但真实 MySQL 重复读及 EJB/JTA 仍须专项验收，不能随意把旧快照调用者加入这条路径。

## 4. 最近测试：局部通过不等于整合通过

| 检查 | 实际结果 |
| --- | --- |
| Task2 迁移离线 | 主代理36项005＋9项历史004，native0；不是实际 MySQL 迁移 |
| Task3 当时全构建测试 | 660发现、595执行、65可选跳过、0失败/错误，native0；这是该源码时点，不覆盖后续 WIP |
| Task4 作者模块 | 543发现、478通过、65可选跳过、0失败/错误，native0 |
| Task4 主代理独立兼容 | 96执行、0失败/错误/跳过，native0；实际教师 JSON provider，仍非部署后 JTA |
| Task5 Fix1 作者 | 两个真实假HTTP反例先失败；最终 Portal144＋Contract21均通过，native0 |
| Task5 主代理独立门户 | Portal144发现，143通过、1失败、0错误/跳过，native1；失败为旧二进制超时测试，不是新严格解析用例 |
| Task6 作者最后离线检查 | PS5.1的35项通过、native0；主代理仅核实两个脚本语法，不是独立功能复测/审查或真实环境验证 |
| 新医院 MySQL 草稿 | 仅编译/发现32项，**32全部可选跳过**；native0不表示32项通过 |

主代理门户运行中新功能相关四个类 `PaasSafeErrorTest`、`PlatformAdministrationPortalTest`、`PlatformAdministrationJspTest`、`PlatformPortalTest` 合计22项通过；独立全门户仍失败，不能改写为144通过。

整合有两个必须继续处理的问题，原失败保留：

### 4.1 MinIO 并发测试把独立请求误当共同截止时间

文件：`course/paas/src/test/java/com/medcloud/course/paas/attachment/PrivateObjectStoreTest.java`，`concurrentCallsShareDeadlineAndStopAfterExpiry`。实际整合曾期望1个请求却观察到2个。

`MinioPrivateObjectStore.execute` 为每次调用计算独立截止时间；释放一个起跑 latch 不能保证三次调用同时开始。私有诊断一次0/250/500ms错峰复现了计数失败，第二次 HTTP 在该调用仍有约109ms有效预算时发起，不能据服务器稍晚收到请求认定“过期后才发起”。原 latch 的一次诊断通过不能抹去失败，也不能证明原失败的具体调度。

下一步应先修正测试语义：测试持有真实 `preflightLock`，让所有等待调用耗尽自己的预算、返回503且 bounded join 完成，再释放锁并确定所有 worker 排空，断言等待调用总 HTTP/对象调用为0。保持现有单请求和 trickle 取消测试及原时限；不能简单放宽请求数、加长超时或重跑到通过。此方案是诊断建议，**尚未实施/复审**。

### 4.2 门户二进制 trickle 的总期限未可靠约束

文件：`course/portal/src/test/java/com/medcloud/course/portal/AttachmentClientTest.java`，`slowChunkedResponseCannotExtendTotalDeadline`；源码 `web/PaasClient.java` 的 `putBinary`。主代理原失败约4.061秒，超过既有 `<3500ms` 断言。

私有诊断观察到单线程 watchdog 在 JDK 的 `ChunkedInputStream.close → disconnect` 阻塞，调用者同时在同步 read 路径；一次有限 trickle 和一次持续 trickle 探针都在约1.1秒返回，因此**未重现原4秒调度，也未证明已修好**。二进制响应读取循环目前不逐次检查绝对 deadline，仅在读完后检查。建议下一位 AI 先写可稳定暴露问题的反例，再考虑读循环/剩余预算或传输机制的最小修正，并验证阻塞上传、并发 watchdog、1MiB响应上限、精确字节、禁止跳转/隐式重放及线程回收。不要把仅加检查就视为保证已证明。

诊断仅改项目 `.local/builds/hob-deadline-eeacc89` 私有副本，未改产品源码。新构建不要从该带诊断插桩的副本部署。

## 5. 医院八任务计划停在哪里

| 任务 | 接续状态 |
| --- | --- |
| 1 共享契约 | 已提交、已审查 |
| 2 保护迁移 | 已提交、离线已审查；实际边界仍归6/7 |
| 3 数据库业务 | 已提交、已审查；真实锁/JTA归7 |
| 4 API | 已提交、已审查；部署实机归7 |
| 5 门户 | 已提交、Fix1复审关闭；整合附件回归仍失败，浏览器归8 |
| 6 新独立环境 | 创建器及离线测试是 **WIP候选，未独立审查、从未实际运行** |
| 7 MySQL/验收驱动 | Java32例是未审查草稿，实际0执行；两个PowerShell交付文件尚不存在 |
| 8 浏览器/全回归/最终审查 | 未开始；不要合并或宣称本批通过 |

明确标记的 WIP 文件：

- `course/deploy/scripts/new-hospital-onboarding-fixture.ps1`
- `course/deploy/tests/hospital-onboarding-fixture.Tests.ps1`
- `course/paas/src/test/java/com/medcloud/course/paas/MySqlHospitalAdministrationTest.java`

即使这些文件已作 WIP 提交，也不改变“未审查、不能直接对真实环境 Execute”的状态。下一位 AI 先审阅作者保留的问题，再离线验证和独立审查，不要重新从空文件重写。

Task6 创建器的候选入口参数：`RuntimeRoot, MySqlBin, JavaHome, TomcatHome, WildFlyHome, MinioExecutable, MinioClientExecutable, SourceCommit, NewRunId, Execute`；默认应退出4且无服务/SQL。候选创建全新 `.local/runtime/attachments-<32hex>`，主库新建005，辅助独立 MySQL 从精确004升005；两个数据库名都是 `medical_cloud_course`，不能通过删DDL伪造004。

候选共享接口：仅点源新创建器的无main部分，`Read-OnboardingFixtureContext(absoluteFixturePath, expected32hexowner)` 读取005运行描述并重建 context；`Assert-OnboardingIdentity(context, component)`、`Assert-OnboardingMysqlLive(context, mysqlName)` 分别验证进程/监听/离线身份及显式实时数据库身份。**接手时必须核对这些函数是否已实际落盘和通过测试。** 不要盲用旧 `Assert-OwnedAttachmentsFixture`，其 PaaS offset 规则与新直接端口布局不一致；不修改旧冻结工具来迎合新环境。

Task7 草稿作者已指出必须补强：不能排除整张 user_account 来证明其他账号没变；writer-first 应断言各业务精确字段而非仅“快照不同”；观察者 URL 要严格解析校验；竞争失败路径要证明所有线程/连接释放。当前锁观察 SQL 用 `INNODB_LOCK_WAITS/INNODB_TRX`，在本机 MySQL8与教师5.6的实际可用性必须分别核对，不能写成跨版本已兼容。`REQUIRED=true` 缺夹具的反例尚未执行；现有本地构建助手会清空 COURSE_* 环境，不能直接拿它执行该负例或真实测试。

## 6. 旧附件/诊疗切片不能丢失的状态

- 附件业务已实现，不重建。验收驱动19必需门中历史7通过/12未验证；真实PDF、300秒到期403、停MinIO等独立观察不能自动填满驱动。
- 病情补充、私有草稿、追加更正已实现。补充仅BOOKED/IN_PROGRESS可写；完成/取消只读；草稿仅原医生、无订单；原病历/订单冻结，更正保留前后快照/原因/操作者/时间/幂等键；更正历史每页最多5条是用户已确认例外。
- 临床HTTP历史23/53通过、30未验证。004迁移七项实机保护门已有通过证据，不重新跑已消费操作。原九例RESOURCE_LOCAL并发/回滚及四个等待观察可有限复用，不是EJB/JTA证明；新增反向草稿竞态属于十例中的新增真实未验项。
- 临床真实JTA曾有4通过/1失败/1未验证；更正请求在登录4110秒后发生401，超过3600秒令牌期，未进入更正业务。不是更正回滚已通过，也不能直接认定业务回滚缺陷。新独立更正验收需新目标、请求前重认证、精确触发器恢复及健康检查，不延长生产TTL。
- 新十例调用助手曾连续在参数覆盖、长路径、Git换行pin等预检阶段失败，最新消费号 `71cce2b95eee43999e8d91af14aca30c` 为phase0/native3、未执行实际十例。第三次后提出的临床完整预检重构/Fix4 **未获用户单独批准**；医院开通批准不能替代它。
- 临床更正专用W6助手离线36项及审查通过，但实际未执行。原调用号、目标、失败记录不可重放/覆盖。

详细规则与旧门列表在 [临床验收指南](course-labs/2026-10-16-clinical-supplements-acceptance.md)、[附件验收指南](course-labs/2026-10-12-attachments-acceptance.md)。历史 [18日交接](AI-HANDOFF-2026-10-18-CLINICAL.md)医院批准段落已经过时，以本文及已批准18日医院设计/计划为准。

## 7. 运行、构建和从 Git 重建

先读 [课程部署说明](../course/deploy/README.md)，再确认当前源码、工具、实例身份和权限。不要直接运行顶层旧版快速开始，不要看到旧登录URL就称当前服务仍运行。本轮没有启动、迁移、停止或替换任何真实医疗云实例。

本机旧临床/附件数据曾在项目 `.local/runtime/attachments-6910fd2545ca4cf6ae88e94fb1203785`，历史登录页55613；其18日私有恢复描述仅是当时快照，**当前活性未核实**。不得向它升级005。恢复服务前重新检查 owner、PID创建时间、完整路径、监听及数据库UUID；已经消费的恢复脚本不是启动按钮。

不含真实凭据的构建入口，在 Java8及项目内隔离源码副本中执行：

```powershell
# 工作目录为隔离源码副本；TEMP/TMP/java.io.tmpdir必须另设项目私有目录，finally恢复。
# 此处是构建命令，不是服务启动/建库命令。
mvn -o -f course/pom.xml -Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final clean verify
```

本机 Maven 位于 `D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`，JDK8位于 `D:/Java/jdk1.8.0_341`。离线 `-o` 缺依赖时应报告并通过正常权限补依赖，不能把缺依赖当业务RED或偷偷升级。IDE/终端是否含这些PATH需自行检查；不同电脑按已验证来源定位工具，不硬套D盘路径。

缺少真实夹具的集成测试可能SKIP，必须单列。不能通过跳过旧失败得到“全绿”。教师JSON provider缺失应失败，不用自制JSON reader冒充实际RESTEasy。

从Git恢复只需源码、设计、SQL、脚本和文档。`.local`/`.superpowers`是本机私有证据，不要求把它们上传；换电脑没有旧证据时，根据已提交源码生成**全新**私有夹具、账号和新编号重新验，不编造已观察门。固定源码归档须检查完整commit、所有entry安全路径/UTF8、Windows短路径、Git CRLF差异；只允许明确验证后的CRLF→LF比较，不能关闭pin。

MinIO工具来源需保留：本机源构建固定官方 `minio/minio@9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`（DEVELOPMENT.GOGET），官方mc固定 `7394ce0dd2a80935aded936b09fa12cbb3cb8096`。源构建SHA256分别为 `1e534498e2468a4b82c2e98300a883492af3107e666af35aba9875ef7853162f`、mc `c8db13ebeda31497f354c0e950809db0ae9b2a2a69b8afee68c128c37300c157`。保留归档/无安全更新/AGPL/非生产局限，不从不明镜像下载、不把源构建冒充官方预编译发布版。

候选IAM ZIP格式已对官方固定源码核对：`iam-assets/policies.json`、`users.json`、`user_mappings.json`；madmin-go/v3 v3.0.109账号字段为secretKey/policy/status，enabled；MappedPolicy为version=1/policy/updatedAt。mc `--json admin cluster iam import` 输出直接是added/skipped/removed/failed对象，**native0也可能部分失败**，空输出及缺失目标用户/策略/绑定不能视为成功。不得用秘密argv或匿名桶绕过。

## 8. 本机可选证据索引与审查结论

本机医院协调记录：`.superpowers/sdd/2026-10-18-medical-cloud-hospital-onboarding/`。读取当前 `progress.md`、Task4/5报告及审查、Task5 Fix1复审、Task6/7最终停点报告、deadline诊断报告。旧记录正文中的“正在运行”须由更新段落/最终native结果覆盖，不能看到一个旧句子就重放。

私有原失败：项目根 `.local/tests/onboarding-root/task5-full-regression.log`、`task5-first-failed-paas-reports.zip`、`task5-fix1-root-portal.log`、`task5-fix1-first-failed-portal-reports.zip`。这些不上传；Git侧读本文的具名结论并重新验证即可。

尚未丢弃的Minor：门户session泄密断言只检查toString，需改成实际安全DTO字段检查；API公共错误测试helper条件式检查request-ID，需强制存在；预期SQL/RESTEasy/Hibernate/compiler/shade/CRLF噪声需准确保留、适当捕获断言，不伪造“输出纯净”。本批最终整体review必须逐项裁决，不能静默遗忘。

## 9. 下一个 AI 的第一轮行动

1. 读取本文、任务清单、AGENTS、医院已确认设计/计划；检查Git当前分支/提交/未跟踪文件，保护原改动。
2. 确认本机没有本轮遗留实际操作窗口；本轮未操作真实服务。检查WIP三个文件及其作者停点，不把它们直接 Execute。
3. 按任务清单 H01 处理两项超时问题并独立review，保留原失败；再完成 H02/H03/H04，而不是先新加业务。
4. 并行仅限独立文件/独立target；所有真实环境操作串行。每个批次写清真实执行、可选skip、mandatory未验及恢复状态。
5. 其后按 R01–R12闭合系统余项；决策待确认的先提具体方案，不自行扩大范围。
6. 交回用户时给出本文末尾任务清单中的review返回包，不自动合并或宣称全系统完成。

本轮用户允许提前交接，不表示剩余任务被取消；停止扩展是用户选择，系统当前不是完成态。
