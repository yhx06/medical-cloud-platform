# 医疗云接续：GitHub 备份与本地空间治理（2026-10-17）

本文件优先于旧交接中的存储、迁移和推送状态。业务实现与验收细节继续参考 [诊疗交接](AI-HANDOFF-2026-10-16-CLINICAL.md)，不能把本次备份与清理视为全系统完成。

## 当前用户要求

最新状态（续至 2026-10-18）：课程源码修复已提交 `117ca3f`；主 runtime 已实际迁至项目 `.local/runtime` 并从新目录恢复。已盘点的旧 C 源码、五份附件 runtime、Task8、10 张截图及两个审计 data 根均已核验归档/移除原件；以下“第五轮”为最新结果。未扫描整个 C 盘，不宣称机器不存在其他未盘点资料，也不代表系统已开发完成。

- 所有项目产物必须最终集中在 `E:/云服务工程/云服务项目-医疗云` 内，不是仅放在 E 盘。
- 尽量将项目代码和文档上传既有私有 GitHub 仓库，减少本地重复副本。暂停新增大体积全量归档副本。
- 旧 C 盘副本仅在完整校验一致且确认完全停机后才可移除。该条件批准不等于已经完成校验，也不允许绕过自动安全审查。
- 保留未提交工作、唯一验收证据和一套可运行环境。密码、令牌、数据库、原始临床内容不得上传 GitHub。

## GitHub 实际状态

仓库：`yhx06/medical-cloud-platform`，通过 GitHub CLI 实测 `isPrivate=true`。

2026-10-17 已将课程开发分支的 35 个既有本地提交推送到 `codex/course-environment`，从 `1e345c5` 更新至：

`1c4d84abe98a083a0aee37ffee523b4a2f20ea25`

随后通过 `git ls-remote` 独立确认远端提交号与本地 HEAD 完全一致。未强制推送、未合并默认分支、未上传未提交源码。此次检查覆盖 106 个待推送 blob：未发现所检查的私钥、GitHub token、AWS access key 或嵌入 URL 的密码模式；这不是全面安全保证。首次宽泛 URL 模式误报的是 PowerShell 哈希表语法，收紧模式后重新扫描为零命中。待推送差异 `git diff --check` 通过。

本交接本身将另作小型文档提交；接续时应重新读取实际 HEAD 和远端，而非假定仍停在上述提交。

GitHub 用于保存源码、需求、设计、脚本和脱敏报告，不是数据库/依赖包/运行目录的文件同步盘。推送本身不会释放本地空间；本次释放量来自下述定向清理。

## 已完成的本地清理

一次性操作脚本（本地私有，不进入 Git）：

`E:/云服务工程/云服务项目-医疗云/.local/evidence/cleanup-rebuildable-20261017.ps1`

默认只预检，显式 `-Execute` 才清理。已完成独立只读审查，无 Critical/Important；root 实际执行会话 **95673 退出 0**。

执行清单：

`E:/云服务工程/云服务项目-医疗云/.local/evidence/cleanup-e5753154dbd6420eadbbbf7c74c57b15.json`

32 个精确目标全部完成，合计 **1.486 GiB**：

- 7 个历史源码快照内 28 项重复编译包/展开包，约 784.48 MiB。
- 3 个仅用于 Unicode 路径身份查询、已正常关闭的新空 MySQL 探针数据目录，约 536.78 MiB。
- 历史前端 npm 内容下载缓存，约 200.56 MiB。

保留了源码、POM、7 个 `surefire-reports` 目录、JSON 测试夹具、日志、独立 SDK probe WAR，以及三个空探针的 fixture/闭合摘要/捕获。未删除 C 盘文件，未删除业务数据库，未移动或停止当前服务，未清理其他脏工作树。旧编译包可从保留源码重新构建，npm 缓存可重新下载；空探针数据目录已释放，不能再依靠旧 fixture 启动或验证该已销毁实例，应使用新的独立实例。

操作前及逐目标删除前检查固定路径、祖先/子树无 reparse、无可见进程路径引用、独占读取及文件集合/长度/SHA 一致。操作不提供跨整个目录的原子锁，不覆盖 ADS/空目录变化，因此不得在并行写入这些历史目标时盲重放；失败应先读 journal。原脚本和清单保留以说明删除了什么，不是未来无限清理的通用许可。

### 第二轮：移除已有完整备份的 C 盘重复环境

root 实际会话 **84198 退出 0**，移除了以下五份旧 C 盘附件测试环境的原目录：`C:/Users/yhx/attachments-` 后接 `219c`、`419c`、`619c`、`819c`、`a19c`，再接共同后缀 `5c4cecf34be4b3f6e04e8551054b`。完整恢复副本保留在项目 `.superpowers/runtimes/attachments-<同一ID>`。本轮不新复制大文件，不上传数据库或凭据。

删除前两次只读审计及执行时的新鲜审计确认：**1,672 文件、2,018,279,213 字节（约 1.880 GiB）**，每个源的全部普通文件/附加数据流/目录均包含于现有 E 备份且 SHA/长度一致。前四份备份无额外文件；a19c 的 E 备份多一个既有归档 provenance 文件。独占读取实际占用负例通过。每个源删除前再次验证源未改变、备份完整、无 reparse、无可见进程路径引用；删除后再次验证保留备份的全部原流。本次经过独立审查，并向自动安全审查提交了范围更小且附有新证据的新请求，获准后才执行，不覆盖或绕过先前十目录方案的拒绝。

执行清单：`E:/云服务工程/云服务项目-医疗云/.local/evidence/c-duplicate-removal-1ff16d0a52fb406a8ee1ce510357d70c.json`。root 删除后复核：5/5 为 `ORIGINAL_REMOVED_RETAINED_BACKUP_REVERIFIED`，5 个 C 源均已不存在，5 个 E 备份均存在，合计字节与清单一致。**两轮累计释放 3,614,027,875 字节，约 3.366 GiB**（普通文件/流字节，不等于卷实际可用空间变化）。登录页和 MinIO health 删除后均实测 HTTP 200。

新脚本 `.local/evidence/audit-existing-c-attachment-backups-20261017.ps1` 与 `remove-verified-c-attachment-duplicates-20261017.ps1` 均已消费，源已移除，**不可重放**。初始只读失败记录保留：C 源既有 sandbox SID 只读授权与错误的复合 Modify 掩码、以及 E 备份 284 字符路径的 ADS 枚举限制，均先只读定位再修正检查；未改 ACL，未跳过任何流。E 备份保持私有。限制如实保留：句柄逐文件关闭而非全程目录锁，ACL检查只覆盖根；两个受保护 mysqld 的命令行仍不可读取，不能声称已查明它们的归属或已停止它们。实际可见四组件均属于当前 E 根 runtime，本轮没有操作任何服务。文件内容恢复副本已经核验，未做数据库启动恢复演练。

### 第三轮：主 runtime 同卷迁移并恢复

原 `E:/medical-cloud-platform-runtime` **已不存在**，全部内容现在位于 `E:/云服务工程/云服务项目-医疗云/.local/runtime`，没有再复制一份展开目录。移动前只读检查发现原四 PID 已消失且七端口无监听；本轮没有先停止它们，也没有证明此前是干净关闭，不能推断退出原因。

执行分三段，保留失败证据，不盲重放：

- **69018 退出 1**：整目录同卷改名成功，9,144 文件、4,394 目录的全部普通/附加流及结构前后核验一致；随后 `Set-Acl` 收紧权限遇到 `SeSecurityPrivilege` 错误。原 journal 保持 `RENAMED_ALL_STREAMS_VERIFIED` / `complete=false`，不能伪改为全程成功。
- **19472 退出 0**：独立审查的窄接续，用经过小目录验证的 DACL-only API 设置目标根仅当前用户可访问，不写审计 SACL/owner/group；保留既有受保护凭据/app ACL。新建 candidate 和独立 continuation journal，未重新搬数据。
- 新恢复脚本 `start-relocated-clinical-20261017.ps1` 的 `-PreflightOnly` 通过，**87381 退出 0**：同版本 MySQL/MinIO/WildFly/Tomcat 从新根启动；新 boot 在 `clinical-application/boot-relocated-20261017`，记录新 PID/创建时间，TEMP/TMP/MySQL tmpdir 均在项目内。没有初始化、播种、数据库升级或更新 WAR。

新权威描述文件为附件 SDD `fixtures/6910fd2545ca4cf6ae88e94fb1203785/clinical-relocated-20261017-fixture.json`，两个 current 指针均已更新。root 新鲜完整四组件 guard 通过，当前 PID：MySQL 2972、MinIO 23448、WildFly 26032、Tomcat 27984；只能作本检查点快照，之后操作仍须重新验证身份。

独立实际 HTTP 复验：登录页 `http://127.0.0.1:55613/portal/login`、MinIO health 与 `http://127.0.0.1:55615/paas/api/catalog/hospitals` 均 200，后者 `code=OK`、两条目录记录，证明实际后端/EJB/数据库可读，不是仅端口存活。只读 SQL 汇总：12 表、预约 4 COMPLETED / 2 CANCELED / 1 BOOKED、病历 4、订单 4。没有进行临床操作或重放已消费的预约。一次初始 HTTP 检查因 PowerShell 数组拼接优先级构造了错误 URI，404 不代表产品故障；修正检查后如上。普通 `ReadAllBytes` 读取活动 MySQL 日志发生共享冲突，产生的假 false 诊断不作启动或 crash-recovery 结论，完整 guard/HTTP/SQL 是实际依据。

三份旧 E 根 build 目录实际已在项目根内，其 E 根原路径不存在；本轮只核实项目内 `clinical-root-task4-3e773f093e8e` 的两个 WAR 与部署 SHA 一致，并在新描述文件校正过时的 `verifiedBuild` 引用，未声称本轮重新搬了三份 build。旧描述/证据中的绝对路径保留原样。

本轮五个全新空 Unicode 探针已正常关闭，随后经独立审查清理其 `mysql-data`，回收 **938,092,983 字节（0.874 GiB）临时占用**；fixture/summary/日志保留。journal 为项目 `.local/evidence/probe-cleanup-fcd82f94f6b345bd9efa9f0c3fa6ce21.json`，5/5 移除且复核无残留。这是本轮测试创建后回收的临时空间，不叠加成第一、二轮净释放量。所有已执行的 Move/接续/Start/清理脚本均已消费，失败或进程丢失后必须读新 journal 制定接续，不能重放。

### 第四轮：旧 C 源码压缩归档及截图归拢

仅移除了旧源码叶目录 `C:/Users/yhx/.codex/worktrees/course-environment/云服务项目-医疗云`，未删除其 Codex 父目录，也没有操作当前三个 E 盘工作树。完整私有压缩档现在位于项目 `.local/evidence/c-source-compact-8c13cc385d114517bbf6b697137ac34f/course-environment-source.verified.zip`；同目录保留源校验清单、归档 journal 和独立移除 journal，不上传归档或原始清单到 GitHub。

- 原件 **29,119 文件、7,675 子目录、3,218,528,104 字节**；隐藏文件、忽略文件、历史证据及 `.git.migration-backup` 均保留。未发现 reparse、活动 `.git` 或附加数据流。只读独占 SHA 审计 **58577 退出 0**。
- 归档 **82184 退出 0**：29,119 文件及包含根的 7,676 目录条目，逐条解压 SHA、实际长度、CRC 和完整名称集合均与基线相符。ZIP 大小 **1,869,846,075 字节**；原件约 3.22 GB 压缩为约 1.87 GB，小型校验记录另计，不把这两个数值当整盘实际空间测量。
- 条件移除 **52387 退出 0**：独立重验完整 ZIP，重新独占读取全部源文件并比较所有字节/目录/ADS，复核路径、权限及可见进程引用；长源复核之后再次检查归档整体 SHA，才精确移除该叶目录。删后归档整体 SHA 再验一致，旧源确实不存在。最终 `source-removal-journal.json` 为 `EXACT_ORIGINAL_REMOVED_RETAINED_ARCHIVE_SHA_REVERIFIED`；较早归档 journal 的 `sourceRemoved=false` 是归档时保留原件的历史快照，不应篡改。

归档工具先做真实小样本及反例：JSON 类型漏检、文件/目录 CRC-only 损坏、文件与目录属性错配曾实际 RED，再修正并通过；最终有效/JSON 往返、16 个拒绝例和本机 tar 的隐藏/中文/超过 260 字符路径/空目录验证均通过。此机 tar 需显式 `zip:hdrcharset=UTF-8`，默认旧编码的失败未隐藏。工具是本机 PowerShell7/.NET10 维护证据，不是教师 Java8 课程应用的新运行依赖。归档是文件字节级恢复副本，**不是完整 NTFS 元数据镜像或数据库启动恢复演练**；独占读取逐文件关闭，不是全树锁。恢复时先建立项目内私有目录，不重启旧脚本或把 `.git.migration-backup` 改回活动 Git 指针。

旧 C 可视化目录中的 **10 张医疗云验收 JPG、732,541 字节**已迁入项目 `.local/evidence/legacy-c-visualizations-20261017/`。**89f47e 退出 0**：全部副本与原件复核、逐个删除前再比双方 SHA/长度并重查路径/ADS、删除后副本再验；保留 UTC 时间及仅当前用户访问的目标权限。仅移除这 10 个原文件，C 可视化 workspace 根保留为空，未解码图片、上传或顺便删除其他图件。首次自动审查网络失败未执行任何动作，原样重试经审查后执行，不是绕过拒绝。

两个 SQL 资料 current 指针也已修正到新项目 runtime 中存在且私有的目标，凭据 owner/UUID/测试库格式已与当前描述文件交叉核对。**这仅修正路径，不证明旧 SQL once 脚本可以重放**；旧 fixture、SQL 脚本及已消费预约保持历史状态。C 盘 Task8 本轮只有可见进程引用检查和单个 `ibdata1` 独占打开探测，不能据此宣称全树已停机或允许直接移除。

### 第五轮（2026-10-18）：旧 Task8 与两个审计临时 data 根

只读审计 **17481 退出 0**：`C:/Users/yhx/.codex/task8-runtime-20260928` 为 **1,649 文件、980 子目录、429,960,073 字节、ADS 0**；两个既知审计临时 data 根分别为 **0 文件/5 子目录**和 **0 文件/9 子目录**。保护端口 3318/8180/8188/10090 无监听、可见进程引用为零、全部源文件独占 SHA 读取通过；这是当次检查，不是历史干净关机或数据库可恢复性证明。未启动旧脚本，也未停止任何服务。

归档/条件移除 **37788 退出 0**，私有目录为项目 `.local/evidence/remaining-c-runtimes-f57e04a882574cf5acaccb34a92646f4/`：

- `task8.verified.zip`：**201,086,640 字节**。完整解压 SHA/长度/CRC/目录集合与源基线一致，重新全量独占核对源、再查权限/路径/端口/进程及归档整体 SHA 后，才移除唯一旧 Task8 根；删后归档再验一致。
- `audit-temp.verified.zip` / `acceptance-temp.verified.zip`：**984 / 1,904 字节**，分别保留原空目录结构。两个源有额外写权限，因此没有沿用 Task8 的递归删除：全部空树复核后，只逐层调用非递归空目录删除，删除前检查路径和目录 ADS。出现新文件、子目录或被观察到的命名流即拒绝，不递归删除新内容。检查至删除仍非全程锁，journal 保持 `wholeTreeLocked=false`。

三个原根均不存在、三个 ZIP 均保留且私有；`relocation-journal.json` 为 `complete=true`，三项均 `EXACT_SOURCE_REMOVED_RETAINED_ARCHIVE_REVERIFIED`。两个 C 审计父目录仍保留为空，没有顺便删除父目录、Codex 设施或共享工具。原审计清单中的 `sourceRemoved=false` 是移除前快照，不改写为当时已成功。

独立审查曾阻止统一源 ACL 门下直接处理可写 Temp 根；随后用真实 RED/GREEN 验证空树删除、新文件/新目录保留、三种路径逃逸拒绝。目录 ADS 新增反例也先 RED，再加入每个目录删除前的检查后 GREEN。完整本机维护套件现通过零文件纯目录 ZIP、真实 tar 空树及此前 16 个归档拒绝例。所有脚本均已消费，默认归档也会消费 journal，不能重放；失败必须读取 journal 后制定窄接续。

备份包含历史测试配置、SQL 和日志，只保留在私有项目目录，**不上传到 GitHub**。它们是文件字节级副本，不是 NTFS 元数据镜像或数据库启动恢复演练。当前运行医疗云的四组件、教师 D 安装和业务数据均未修改；本轮没有新增业务功能或临床验收结果。

## 仍需处理的空间与路径问题

首轮只读盘点（不跟随 junction）显示：指定 E 盘项目根约 8.824 GiB，旧 E 根 runtime 约 2.350 GiB，已知 C 盘旧源码/Task8/附件副本合计约 5.28 GiB。均为清理前普通文件字节统计，不等于 NTFS 实际分配空间，也不是全磁盘统计。不要用这些旧数值声称当前精确剩余占用。

1. 主运行环境已完成上述迁移和实际恢复，旧 E 根路径已不存在。未来仅使用新 current 描述文件；不要为规避中文路径问题重新建立英文 E 根 runtime，也不要移动正在运行的 MySQL。
2. 已盘点的 C 原件已按第二、四、五轮全部处理；Task8、源码、截图及两个审计 data 根不能再列为待处理。空 Codex 可视化根、空审计父目录和 Codex/Git 工具元数据不是待递归清理目标；共享技能/插件、Maven 缓存及教师安装不属于本项目专用资料。原十目录全量归档方案未执行，原件现已不存在，不能重放。没有新增约 5.28 GiB 的完整展开副本；源码与 Task8 保留上述压缩档。
3. `.superpowers` 内仍有历史源码快照、工具和重要验收证据；仅此次白名单编译包已清理，不能整体删除该目录。三个工作树存在用户或尚待审查的改动，不能直接移除工作树。
4. 此前 45 个普通离线测试目录和 20 个含 28 个 junction 的历史测试归档已同卷迁入项目 `.local/tests`；两份执行 journal 均记录 VERIFIED。junction 元数据保持原样，不代表历史夹具仍能启动。主 runtime 已完成，3 份 build 已在项目内，未证归属的空目录仍不能按名字直接删除。

## 开发与验收接续点

权威代码树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，分支 `codex/course-environment`。项目顶层为旧 Spring/Vue 路线，不在那里继续课程版开发。

Unicode Fix2 三文件已独立审查并精确提交为 `117ca3f`：

- `course/deploy/tests/attachments-fixture-ownership.ps1`
- `course/deploy/tests/attachments-mysql-path-identity.Tests.ps1`（新文件）
- `course/deploy/tests/setup-database-encoding.Tests.ps1`

初版严格 HEX/UTF-8/真实 Windows ACP 路径修复已独立审查且 root 七套原生测试通过，但真实探针仍失败。进一步实测显示本机 mysql.exe 不能可靠读取绝对 Unicode 路径的 options 文件；同目录相对 ASCII 路径成功。因此 root Fix2 改为归一化 client 绝对路径、在已验证 options 目录使用相对 ASCII 文件名，并在 finally 恢复位置及 MYSQL_PWD。定向实测 RED 17 场景/106 断言/34 失败，GREEN 同 17/106 零失败；新鲜七套原生回归 **34239 退出 0**，真实中文物理目录 canary **72987 退出 0**、完整身份 guard 与正常关闭均通过。

关闭命令没有盲改：审查曾推测 mysqladmin 也必然受绝对 Unicode options 影响，但新实例实测成功；最终 **68893 退出 0** 在专属空实例锁定 root、确认零未锁定无密码账号，使用需随机密码且仅有 SHUTDOWN 权限的临时虚构账号完成原共享 Stop。认证信息仅走 stdin/私有 cnf，不出 argv/输出。独立审查据此撤销旧阻断，原 Stop 实现不变；人为要求相对参数的 fake-client 4/28/10 红例不证明产品错误，已移出产品套件并归档本地诊断。

完整 Java8 离线 Maven **70265 退出 0 / BUILD SUCCESS**：625 项发现、561 实际执行、64 可选环境跳过、0 失败/错误。初跑 30670 的 10 项 loopback 沙箱错误和 13 项未配置真实 WildFly provider 路径失败已保留小型报告；重跑提供 `-Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final` 并允许测试自身 loopback，未跳过失败项或改源码拿绿色。64 可选跳过不代表实机验收通过，具体外部门仍按诊疗/附件验收清单继续。

保护未跟踪 `docs/course-architecture/Medical Cloud API Probe.eam`、其他脏树、教师 D 盘安装与原保护端口。原实现者 `deployment_utf8_review` 和 `clinical_task6_driver` 曾报告额度错误，不要反复重派或切模型绕过；可复用已完成审查者 `clinical_task6_driver_review` 做明确范围的只读审查。

当前四组件身份应从附件 SDD `current-fixture-path.txt` 读取并重新做归属检查，不能仅凭旧 PID 操作。本轮在清理前后实际访问本地登录页 `http://127.0.0.1:55613/portal/login` 与 MinIO health，均为 HTTP 200；不是业务链全面验收。

诊疗 wave2 已闭合：112 请求，53 必需门中 23 通过、0 失败、0 跳过、30 未验证，`wholeTask6Accepted=false`。不要重跑已消费的 main/legacy 预约。JTA、故障、浏览器及全系统其他缺口仍在；整体系统尚未完成，后续开发范围保留。
