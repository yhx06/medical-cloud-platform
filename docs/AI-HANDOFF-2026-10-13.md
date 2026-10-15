# 医疗云项目 AI 交接文档

> 历史交接，已被 [2026-10-15 当前交接](AI-HANDOFF-2026-10-15.md) 取代。下述HEAD、开发状态、运行身份和下一步均有过期内容；仅供历史追溯，不得作为启动/部署依据。

更新时间：2026-10-14（Asia/Shanghai，E 盘迁移后）。本文件是接续导航，不是全项目完成证明。动态进程、Git 状态和测试结果必须在接手时重新核对。

## 1. 用户目标与已确认事项

目标：完成医疗云系统所有已规划功能及服务，用于云服务工程期末演示、汇报和报告提交。采用多智能体并行开发，每完成一大阶段向用户汇报；不能把当前附件切片完成当成全系统完成。

已批准：

- 课程版技术路线、附件服务设计及实施计划，并明确确认“开始并行开发”。不必重复询问上述审批。
- 本地 Git 写入、提交，以及完成并验证后的推送、合并。私有仓库为 `yhx06/medical-cloud-platform`；当前附件阶段尚未推送或合并。
- 不再制作或修改 PowerDesigner 图，已有图件保留。70 台 IaaS 服务器仅建模，不实际启动。
- Git 系统代理已配置为 `http://127.0.0.1:7890`，用户要求本机项目推送沿用该端口；不要反复更改全局网络配置。
- 只使用虚构或脱敏数据，不接入真实患者资料。

最新用户请求是“将整个项目都放在e盘”。当前课程版代码、未提交修改、全部本地证据和五个专属测试数据目录已复制到 E 盘并校验；Git 工作树登记已迁到 E 盘。C 盘原副本暂留作恢复备份，不再开发。迁移没有新增业务功能，也没有运行全套业务测试，全系统目标仍未完成。详见 `docs/PROJECT-MIGRATION-2026-10-14.md`。

## 2. 必须使用的工作树

**权威开发目录：**

`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`

分支：`codex/course-environment`，已经是 linked worktree。当前 HEAD：

`4efd4dd23db84d5b75dd708ede0517abc0e05cda`

环境可能默认给出 `E:/云服务工程/云服务项目-医疗云`，该顶层仍是历史 Spring/Vue 路线及 Git 公共仓库位置。**应进入其 `.worktrees/course-environment` 子目录继续课程版，不在顶层旧路线开发，也不要新建重复工作树。** 工具命令显式指定上述 E 盘课程版目录。历史日志和 brief 中的 C 盘开发路径已被本节取代；历史证据原文保留。

课程架构：Java 8 → Tomcat 9 Servlet/JSP（Portal）→ WildFly 10 Java EE 7/JAX-RS/EJB（PaaS）→ JPA/JNDI → MySQL。附件使用私有 MinIO。仍只有两个部署 WAR；新增 `attachment-sdk` 是内部 JAR，不是新服务。

## 3. 优先阅读的权威材料

以下路径均相对权威开发目录。

1. `docs/superpowers/specs/2026-10-12-medical-cloud-course-attachments-design.md`：已确认附件设计，发生冲突时以设计为准。
2. `docs/superpowers/plans/2026-10-12-medical-cloud-course-attachments.md`：已确认计划，包含后续实机兼容修订与并行顺序细化。
3. `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/progress.md`：本计划进度、裁决、审查和恢复地图。历史段落有旧状态，应看最新 checkpoint、实际 Git 与报告；存在旧未勾选行时不要误判已批准任务未完成。
4. 同目录 `worker-context.md`、`task-N-brief.md`、`task-N-report.md`：各代理职责与实际证据。给子代理自己的 brief，不要复制整个会话或全部计划。
5. `docs/course-labs/2026-10-04-full-scope-inventory.md`：全系统范围、缺口与冲突，不能用附件范围排除条款删掉这些后续任务。
6. `.superpowers/full-system-goal-progress.md`：整个系统目标接续记录。

`.superpowers` 是 Git 忽略的本地证据区，不随 push 传到另一台电脑。若下一位 AI 在不同机器，必须另行安全传递所需报告，或重新生成验收环境；**不能把凭据、签名 URL 或全部运行目录加进 Git。**

## 4. 已完成并提交的附件基础工作

| 提交 | 内容 | 验证与边界 |
| --- | --- | --- |
| `975e8f3` | 附件服务设计 | 用户已确认 |
| `f172b5d` | 多智能体实施计划 | 用户已确认 |
| `b97175d` | Task 3：schema、003 迁移、保护升级器及测试 | 独立规格/质量审查通过；真实新 MySQL 8.0.45 七项行为测试通过；JPA validate 留 Task 4/8 |
| `728fd15` | Task 1：DTO、内部类型、SDK-free 接口等初始保存点 | 公共契约核心已审查，不能单凭此提交认定兼容门通过 |
| `03831cc` | Task 9：新夹具创建及归属、正常关闭工具 | 真实独立四组件创建与生命周期测试 |
| `22b3ae5` | Task 9 审查修复 | 先核 MySQL 监听归属再发送带凭据查询；补 MinIO 控制台端口检查；45 项断言通过，独立 Fix1 复审批准 |
| `4efd4dd` | Task 1：私有 SDK 隔离及构建、探针、文档修订 | 11 个明确文件；独立 Fix1 复审无 Critical/Important；安全清单已补核对 |

### Task 1 的最终方案与真实证据

- `course/attachment-sdk` 生成私有 JAR，将 `io.minio` 重定位至 `com.medcloud.course.paas.sdk.internal.minio`，将其 Jackson 重定位至 `com.medcloud.course.paas.sdk.internal.jackson`。
- 版本：MinIO 8.6.0，私有 Jackson core/databind 2.21.7、annotations 2.21；PDFBox 2.0.37；BC 1.85、Guava 33.7.2-jre、实际 PaaS Commons IO 2.14.0 等见最终 dependency tree。
- 不替换教师 RESTEasy，不升级教师容器/JDK，不改公共 DTO 注解。最终产品/探针 **没有** `jboss-deployment-structure.xml` 覆盖。
- 私有 JAR/shade 和 contract JAR 在 `process-classes` 产生，PaaS WAR 在 `process-test-classes` 产生，使普通 `clean test` 不依赖预先安装内部 SDK、旧 target 或先 package。
- 保留许可证、NOTICE、服务资源；私有 JAR 仅保留 Java 8 base 类，过滤上游签名/module/MR 变体。外部 BC 等原始 JAR 未擅自修改。
- MinIO 原始发布 JAR 没有 Maven pom.properties，运行版本标识是 `dev`。不伪称其 runtime marker 为 8.6.0，改用实际 Maven 坐标、输入 SHA 和来源记录证明。
- 独立官方 SHA256 与本地输入一致：`295b5aec09e39ac4456a94577763477b025c3861d1dde0d23af8223432c80a77`。
- 真实 Java 8/WildFly 10 的 17 项限定检查通过、0 跳过：SDK 实例化，实际 RESTEasy ApiResponse/PrepareUploadRequest 读写，原 RegisterRequest 额外字段忽略行为及 TenantStatusRequest 正常/未知字段行为。
- 主代理最终 `verify` 成功：422 项发现、385 执行、37 项可选 MySQL 跳过、失败/错误 0。SDK 实机门没有跳过。验证的是保留的 Task 1 源码快照与旧 Portal 基线，不包含正在开发的 Task 7 页面。
- 主代理核对九个源/构建文件 SHA，与该完整测试快照完全相同后才提交。输出有 compiler/Hibernate/Tomcat/Shade 警告，不是“无警告”。

核心证据（同 SDD 目录）：`task-1-root-final-verify.log`、`task-1-root-final-trees.log`、`task-1-report.md`、`task-1-fix1-review.md`、`security-audit-20261013.md`。

**不要重试废弃方案：** 原 SDK 被教师 Jackson 2.5.4 覆盖导致 VerifyError；随后 import-filter 虽简单 DTO 通过，却破坏 RegisterRequest 注解行为。两次失败已留证，最终是私有 SDK 重定位，不是继续修改容器模块或 DTO。

### 安全核对不能遗漏的限制

最后一次 OSV 公共精确版本查询：31 个输入、31 个响应、14 条命中，全部来自保留的公共 Jackson 2.15.4 core/databind。其他 28 个坐标（包括私有 SDK 上游和补丁候选）在该查询时点未命中。

这只关闭 **本轮 SDK 清单证据缺口**，不修复基线 14 条公告，也不证明没有漏洞或可以生产使用。Contract/Portal 2.15.4、教师 provider 2.5.4、旧容器及 JDK 风险仍在。不要把“保留兼容基线”写成安全补丁。

SDK 树里 commons-compress 选 Commons IO 2.20.0，但实际 PaaS 直接依赖 2.14.0 胜出。尚无具体故障证据，Task 2 必须在实际发货 classpath 验证其 put/head/sign/list/delete 路径，出现真实 linkage 问题后再由依赖负责人修复，不凭版本差异盲改。

## 5. 当前未提交代码：Task 7 门户开发中

最新 Git 检查看到：

- 已修改：`course/portal/src/main/java/com/medcloud/course/portal/web/PaasClient.java`、`.../factory/CloudServiceFactory.java`。
- 新 Java：`AttachmentDownloadPolicy`、`AttachmentMultipart`、`PreparedPortalFile`、`AttachmentServlet`、`AttachmentServletInitializer`、`AttachmentWorkspace`。
- 新资源：`course/portal/src/main/resources/META-INF/services/javax.servlet.ServletContainerInitializer`。
- 新测试：`AttachmentClientTest`、`AttachmentMultipartTest`、`AttachmentPortalTest`、`AttachmentJspTest`。
- 仍有原来无关未跟踪文件 `docs/course-architecture/Medical Cloud API Probe.eam`，**不可删除、修改或顺手提交**。

这些 Portal 文件是授权任务的 WIP，必须保留，不要按“脏树”清理。真实首次 RED 已执行：13 项测试、13 失败、0 错误、0 跳过，证明缺失功能。现在已开始实现，但 **没有最终 GREEN、完整 Task 7 审查、实际后端联调或浏览器验收**。不要依据旧报告开头“尚未实现”否认最新 Git 中已有代码，也不要把代码存在当成完成。

Portal 独占测试快照：

`C:/Users/yhx/AppData/Local/Temp/medical-task7-0f9395ca-3c56-4024-8827-196f69475e74`（历史 RED 快照，不再作为当前构建位置）。接续时从 E 盘保留的 Portal WIP 建立新独占快照，放在 E 盘课程版 `.superpowers/build-snapshots/`；不要清理或重置现有 WIP。后续项目临时文件及缓存也尽量显式放 E 盘，避免默认 C 盘 TEMP。

首次 RED handle `29957` 已终止 exit 1，不需要重新 poll。继续前查看 `task-7-report.md` 的最新 session 与日志，尚未拿到句柄不能猜测有进程在运行。

当前唯一 Portal 写入者句柄：`/root/attachments_task7_finish`（gpt-6-astra high），交接时状态 **interrupted**。同一会话可恢复该句柄；跨新会话若句柄不可用，派一个新代理读取现有代码/报告接管，不从零重写。旧 `attachments_task7`、`attachments_task7_recovery`、`attachments_task7_alternate` 都有 Transport/capacity 失败，**不要同时复活多个写入者**。

授权补充：`views/audit.jspf` 的三种新事件及 FILE_METADATA 标签实际不在简写 Files 列表中，已批准 Task 7 仅补标签与必要行为测试；不能增加管理员附件下钻、文件名/存储键/签名显示。

## 6. 接手后的开发顺序

Task 1 已在限定 SDK/虚构课程范围完成并审查通过，Task 9 工具已完成。Task 3 迁移代码与真实数据库行为已审查/验证，但实体后的 JPA validate 仍是 Task 4/8 交叉门。

**Task 2 与 Task 4 的 brief 已生成，但中断发生在派代理之前，尚未有这两个实现代理。不要把“准备派出”当成已开发。**

建议最多三个子代理并行：

1. 接续 Task 7：完成 multipart、页面、客户端、Servlet/Initializer、CSRF、超时恢复、一次下载授权和审计标签；真实失败测试后实现，最终相关测试、Node、独立审查。
2. 新派 Task 2：真实 JPG/PNG/PDF 解析、SHA、上限与 deadline、并发 gate、配置降级、MinIO 私有适配；使用重定位 SDK，不再 import 原 `io.minio`。实际存储与签名到期测试不可用 Mock 替代。
3. 新派 Task 4：FileMetadata 实体/登记、授权、状态机、短事务、锁序、配额、幂等、分页、成功审计和真实数据库并发。schema 合同看 `task-3-report.md`；不得改 SQL 来迁就错误实体。

之后：Task 4 审查完成再推进 Task 5 API 与 Task 6 编排/回收，Task 5 的最终实现不得依赖假 coordinator；Task 8 统一完整双容器、对象存储、故障、并发、浏览器及交付验收。每个大阶段准确区分代码、单元、数据库、对象存储、容器、浏览器证据。

并行纪律：主代理唯一操作 Git index/提交；代理只编辑明确独占文件、使用 apply_patch、不派自己的子代理。Task 2/4 同属 PaaS，**各自从已提交 `4efd4dd` 的 course 源码建立独占快照，再只覆盖自己的任务文件**，避免 Maven 编译另一个代理半成品测试。只分开 target 不够。快照不含 `.git`、全部 target、需求书/EAM/无关文件；保留日志。跨任务接口变更由负责人统一裁决，不私自扩签名。

## 7. 运行夹具及归属保护

2026-10-14 开发接续更新：当前新活动夹具为 owner `8148fe66c0594e0b801598a7df0df08f`，运行根目录 `E:/medical-cloud-platform-runtime/attachments-8148fe66c0594e0b801598a7df0df08f`。课程版 `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/current-fixture-path.txt` 指向它的 `resume-fixture.json`（不用原 fixture.json 旧 PID）。主代理独立调用已通过四组件归属检查，并再次核验 MySQL/MinIO。父持活执行句柄 `81202` 在四组件正常关闭前不可终止；接手时先核句柄和身份，不凭此文字认定仍运行。新夹具使用英文 E 盘路径，绕过已证实的 Windows MySQL GBK 路径字节问题，不放宽归属检查。此前启动命令退出后进程全部消失的原因未最终证实。

Task4 私有配置在该运行根目录 `task4-test-database.json`；只允许其唯一 `course_attachment_test_<owner>` 库的测试数据/故障触发器，JPA 使用 validate。该新库已导入 10 表、无 seed。另为后续应用验收在新空 `medical_cloud_course` 导入 10 表，核附件 23 列/6 非级联外键，无 seed/WAR 部署。不得拿测试 app 凭据或根凭据到其他数据库使用。最新代理和测试句柄看 SDD ledger/report；现有源代码 WIP继续保留，容量错误后的终止实现者不得与接管者同时写入。

下面 a19c 是迁移保留的停机历史，不是当前新活动环境。

当前迁移后停机描述文件（不是可直接验收的运行身份）：

`E:/云服务工程/云服务项目-医疗云/.superpowers/runtimes/attachments-a19c5c4cecf34be4b3f6e04e8551054b/e-drive-stopped-fixture.json`

Runtime root：`E:/云服务工程/云服务项目-医疗云/.superpowers/runtimes/attachments-a19c5c4cecf34be4b3f6e04e8551054b`。迁移描述已替换运行目录和工具目录路径，并清空旧 PID/created，状态为 `STOPPED_NOT_ACCEPTANCE_READY`。历史 resume-fixture.json 保留不改，不能当成当前运行身份。

| 组件 | 历史端口（恢复前重新核占用） | 历史 PID（已失效） |
| --- | --- | --- |
| MySQL 8.0.45 | 53205 | 29616 |
| MinIO API / console | 53206 / 53207 | 30052 |
| Tomcat 9.0.24 / shutdown | 53208 / 53209 | 14660 |
| WildFly 10 / management | 53210 / 55120 | 28032 |

2026-10-14 迁移前重新检查，四个保存 PID 均不存在，五个专属运行目录也未发现引用其路径的进程；消失原因未证实，不能写成主动正常关闭。所有测试数据采用停机复制，1,672 个文件 SHA-256 一致，凭据 ACL 已核验。本次没有启动服务。**后续先核验 E 盘配置、端口、目录、UUID 及凭据权限，再安全恢复并记录新的 PID/created；不能只改 JSON 就声称实例已运行。** 完整产品及新门户实机验收仍待完成。

每次 SQL/存储写入、部署、故障注入、关闭前都调用：

`course/deploy/tests/attachments-fixture-ownership.ps1` 的 `Assert-OwnedAttachmentsFixture`。

核 PID、创建时点、可执行文件、目录、监听和 MySQL 离线/在线 UUID。不能仅凭端口号操作。读取 UTF-8 无 BOM fixture 时 Windows PowerShell 5.1 应用 `[IO.File]::ReadAllText(...) | ConvertFrom-Json`；默认 `Get-Content` 曾误解码中文路径。

凭据在该 runtime 的 `credentials.json`、`mysql-client.cnf`，ACL 已限制。**不得输出内容、密码、access/secret、完整签名 URL，也不得放 Git 或聊天**。

已有 `medical_cloud_course` 为 unicode_ci，10 张表；包含迁移验证的 10 条虚构 seed，不是用户真实数据。不要让 ORM 测试自动 drop/create 该库。Task 4 的并发测试应建立标识明确的新独占数据库；每次操作前核归属，不能使用原生产/演示库。

新 MinIO 使用固定官方源码构建，不是官方预编译发布：commit `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`，显示 DEVELOPMENT.GOGET。官方社区下载曾返回 410，仓库已归档；无维护、AGPL、仅 loopback 虚构课程验收等限制必须保留。工具来源及 SHA 在 `tools-source-build-20261012/provenance.md`，不安装或升级全局工具。

历史环境说明：

- `619c...` 夹具的默认 MySQL 8 排序规则导致迁移保护正确拒绝，旧九表/10条虚构行未重置；保留失败证据，不再拿它当正确迁移夹具。
- `819c...` 生命周期夹具四组件已精确正常关闭并验证 PID/监听消失；目录和证据保留。
- 原 a19c/619c 保存进程后来意外消失，原因未证实，不能写成主动正常关闭。通过单次安全恢复 helper 保留数据、原快照、日志，只生成新的 `resume-fixture.json`。
- 原 `fixture.json` 和 `resume-fixture.json` 都是迁移前快照，不要用其 PID 部署或关闭当前进程。
- `.superpowers/.../resume-once.ps1` 保留历史 C 盘硬编码，不得直接运行；应基于迁移后描述完成 E 盘路径适配与安全验证。不要用 creator 覆盖已有目录。中断后先查 partial、日志、PID、句柄，不能仅 timeout 就重复启动。

严格保护：`C:/Users/yhx/.codex/task8-runtime-20260928`、3318/8180/8188/10090、教师安装、其他 MySQL/MinIO、历史备份和无关工作树。集成树曾有 16 项无关变更，合并前重新查，不覆盖它们。禁止全局强杀、递归清理、DROP 恢复、git reset --hard 或 git checkout --。

## 8. 附件必须落实的业务规则

- 两条链路都要交付：患者本人预约资料；医生本人本院已完成病历追加附件。不是二选一。
- 患者 BOOKED/IN_PROGRESS 可上传，COMPLETED/CANCELED 不可。删除仅原上传患者、医院启用、BOOKED 且 started_at 为空。
- 医生只能追加本人已完成病历，不覆盖旧病历、不改完成时间、不重新生成订单。
- 医院停用禁止新写，但启用账号可授权历史读取。患者 tenant_id 可空，归属必须来自预约关联。
- 管理员不得访问内容、文件名、对象键或签名地址。全部替换 ID 与跨院/同院非分配医生矩阵必须验。
- 默认 10 MiB，JPG/PNG/PDF；20,000,000 像素、200 页；真实解析，不只是 MIME/后缀。PDF 拒绝加密、嵌入文件、JavaScript、启动/自动打开及嵌套危险动作。
- 同一行 id 为 uploadId/fileId；六状态及持久意图、attempt fencing、两条上传配额、短事务/共同锁序按设计执行。
- MinIO 等待不在 JTA 中；未知提交先查库，不猜失败删除 AVAILABLE。迟到 PUT、墓碑持续回收、失败退避与重启必须实测。
- 五分钟签名是持有者凭证，不能承诺即时按人撤销；签发事务提交后返回，URL 不进普通列表/session/flash/Location/日志。审计表示授权签发，不表示下载完成。
- 存储停用只降级附件，不阻断预约→接诊→病历/订单→模拟支付。

## 9. 仍须处理的审查项与全系统范围

已记录且最终分支审查须 triage：

- Task 1 依赖文档历史段落混用 latest/final/open，需要明确“已废弃实验历史”，不能删除失败证据。
- DTO 同字面量 ID 测试不证明持久同一行身份，Task 4/6/8 必须验证。
- Task 3 测试的 unused audit 残留变量。
- Task 9 派生 management 端口的启动前空闲检查、六处 source-text 回归测试、CLI 警告。
- Commons IO 两种版本的实际存储调用兼容验证。

完整系统还有附件之外的缺口：病情补充、病历草稿/更正、医院及管理员开通、跨号源时间冲突、完整分页/按 ID 恢复、支付流水号、订单取消规则等。处方与需求排除有冲突，P2 评价/AI 是否纳入全目标需按原确认范围处理，不擅自删减或实施。

还有 50 并发性能、备份及真实恢复、全主要流程 Chrome/Edge、云端部署授权与资源、接口/设计说明、全功能演示、最终报告、汇报 PPT、备用环境和真实成员贡献。MySQL 8 不能称教师 5.6 已验，本地 HTTP 不能称云端 HTTPS 已验；请逐条用完整范围清单复核。

## 10. 工具与 Git 收尾

工具：

- Maven Java 8：`D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`。
- 容器 Java 8：`D:/Java/jdk1.8.0_341`。
- Maven：`D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`。
- Node：`D:/nodejs/node.exe`。

只设置命令/会话 JAVA_HOME、PATH，并运行版本检查，不改系统配置。Task 7 独占 snapshot 可用 `-pl portal -am`；PaaS 各代理只在自己的冻结 snapshot 执行指定测试。必需门的 skip 不算通过，默认可选 MySQL skip 单列。

按 skill 流程：接手读取相关 SKILL、实际工作树和进度；TDD；独立规格/质量审查；问题交回同一实现者；主代理 fresh verification、精确暂存提交。推送、合并必须等相应实机门及全分支审查完成，沿用现有授权但重新核远端、目标分支、脏树、远端 SHA；新 PR 若创建必须附加到当前任务。不要为赶合并跳过实机门。

## 11. 下一位 AI 的起始操作

1. 先核 E 盘 `.worktrees/course-environment` 权威工作树、HEAD、Git status、相关 AGENTS.md，保留所有 Portal WIP 和原 EAM；不再使用 C 盘备份开发。
2. 读本文件及最新 SDD 进度；Task 1/9 不重做，Task 3 JPA 交叉门仍留着。
3. 核 live agents/工具句柄：同会话恢复 Task 7 唯一写入者；新会话只派一个接管者。中断不是重新初始化的理由。
4. 根据已生成 brief 并行启动 Task 2/4，与 Task 7 严格独占文件及构建快照，最大三个子代理；代理不操作 Git。
5. 完成后统一后端联调与 Task 8 实机/浏览器/故障验收，准确阶段汇报，再推进完整系统剩余范围。

本交接 Markdown 本身未提交、未推送，不代表业务或验收完成。
