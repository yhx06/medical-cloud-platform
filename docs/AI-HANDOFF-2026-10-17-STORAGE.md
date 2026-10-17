# 医疗云接续：GitHub 备份与本地空间治理（2026-10-17）

本文件优先于旧交接中的存储、迁移和推送状态。业务实现与验收细节继续参考 [诊疗交接](AI-HANDOFF-2026-10-16-CLINICAL.md)，不能把本次备份与清理视为全系统完成。

## 当前用户要求

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

## 仍需处理的空间与路径问题

首轮只读盘点（不跟随 junction）显示：指定 E 盘项目根约 8.824 GiB，旧 E 根 runtime 约 2.350 GiB，已知 C 盘旧源码/Task8/附件副本合计约 5.28 GiB。均为清理前普通文件字节统计，不等于 NTFS 实际分配空间，也不是全磁盘统计。不要用这些旧数值声称当前精确剩余占用。

1. **当前运行环境仍在 `E:/medical-cloud-platform-runtime`，尚未迁入指定项目根。** 最新目标是项目内 `.local/runtime`，不能再次以英文 E 根作为最终方案。应完成 Unicode 身份修复真实门，再受控停机、同卷迁移和恢复；不能移动正在运行的 MySQL。
2. 旧 C 盘源码、Task8、截图和审计临时资料仍未移除；五份附件原副本已按上节完成移除，不能再列为待处理。旧十目录全量归档后删除请求仍未执行。其余目录的离线/完整恢复证明仍待完成，没有新增约 5.28 GiB 的全量副本。优先核对已经存在的备份或采用经验证的紧凑归档，避免再保存一份完整展开目录。
3. `.superpowers` 内仍有历史源码快照、工具和重要验收证据；仅此次白名单编译包已清理，不能整体删除该目录。三个工作树存在用户或尚待审查的改动，不能直接移除工作树。
4. 此前 45 个普通离线测试目录和 20 个含 28 个 junction 的历史测试归档已同卷迁入项目 `.local/tests`；两份执行 journal 均记录 VERIFIED。junction 元数据保持原样，不代表那些历史夹具仍能启动。3 份 E 根 build、主 runtime 与一个未证归属空目录仍待处理。

## 开发与验收接续点

权威代码树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，分支 `codex/course-environment`。项目顶层为旧 Spring/Vue 路线，不在那里继续课程版开发。

未提交、必须保留且尚不能标完成的 Unicode Fix2：

- `course/deploy/tests/attachments-fixture-ownership.ps1`
- `course/deploy/tests/attachments-mysql-path-identity.Tests.ps1`（新文件）
- `course/deploy/tests/setup-database-encoding.Tests.ps1`

初版严格 HEX/UTF-8/真实 Windows ACP 路径修复已独立审查且 root 七套原生测试通过，但真实探针仍失败。进一步实测显示 MySQL 不能可靠读取绝对 Unicode 路径的 options 文件；同目录相对 ASCII 路径成功。因此 root Fix2 改为归一化 client 绝对路径、在已验证 options 目录使用相对 ASCII 文件名，并在 finally 恢复位置及 MYSQL_PWD。定向实测 RED 17 场景/106 断言/34 失败，GREEN 同 17/106 零失败。**Fix2 仍缺独立复审、完整七套回归及新的真实 Unicode canary，不得凭初版审查放行主 runtime 迁移。** 已销毁的三个空探针不能重放。

保护未跟踪 `docs/course-architecture/Medical Cloud API Probe.eam`、其他脏树、教师 D 盘安装与原保护端口。原实现者 `deployment_utf8_review` 和 `clinical_task6_driver` 曾报告额度错误，不要反复重派或切模型绕过；可复用已完成审查者 `clinical_task6_driver_review` 做明确范围的只读审查。

当前四组件身份应从附件 SDD `current-fixture-path.txt` 读取并重新做归属检查，不能仅凭旧 PID 操作。本轮在清理前后实际访问本地登录页 `http://127.0.0.1:55613/portal/login` 与 MinIO health，均为 HTTP 200；不是业务链全面验收。

诊疗 wave2 已闭合：112 请求，53 必需门中 23 通过、0 失败、0 跳过、30 未验证，`wholeTask6Accepted=false`。不要重跑已消费的 main/legacy 预约。JTA、故障、浏览器及全系统其他缺口仍在；完整开发目标保持 active。
