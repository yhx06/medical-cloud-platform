# 医疗云项目集中到 E 盘

> 2026-10-15接续状态见 [当前AI交接](AI-HANDOFF-2026-10-15.md)。本文是迁移记录；下文活动进程/holder描述为历史。8148环境已于15日逐组件正常关闭，数据保留，不可重用旧PID或执行句柄。

日期：2026-10-14，Asia/Shanghai。用户请求：“将整个项目都放在e盘”。

## 当前目录

- 课程版代码、需求书、设计、交接、未提交 Portal 文件和忽略的验收证据：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`。
- Git 公共仓库及旧实现：`E:/云服务工程/云服务项目-医疗云`；不要在顶层旧 Spring/Vue 路线继续课程版。
- 专属测试数据：`E:/云服务工程/云服务项目-医疗云/.superpowers/runtimes/attachments-<ownerId>`。
- 分支 `codex/course-environment`；迁移 HEAD `4efd4dd23db84d5b75dd708ede0517abc0e05cda`。提交历史及其他分支不变。

## 验证与保留

完整复制 29,119 个项目文件（3,218,528,104 字节），含隐藏文件、Git 忽略的 `.superpowers` 和构建文件。切换 Git 登记之前逐文件 SHA-256 全部一致；随后 `git worktree repair` 成功，Git 状态中原有 Portal WIP 和 EAM 均保留。校验器在顶层 `.superpowers/verify-project-copy.ps1`。迁移后的文档和 Git 指针修改属于有意变更，不能再要求其与旧副本字节相同。

五个测试目录 219c/419c/619c/819c/a19c 共 1,672 个文件、2,018,279,213 字节，停机复制并逐文件哈希通过。目标父目录仅当前用户访问，`credentials.json` 和 `mysql-client.cnf` 的保护性单规则 ACL 已核对。不将数据或凭据提交 Git。

C 盘原代码和运行目录暂留恢复备份；不会自动删除。旧代码备份的 `.git` 指针改名为 `.git.migration-backup`，以免两份目录共享活动 Git 索引。原课件、其他项目、受保护 task8 运行环境、教师安装及全局开发工具不移动。JDK/Maven/Node 等原 D 盘安装保持原样。

## 测试环境状态

后续开发增量：新活动环境位于 `E:/medical-cloud-platform-runtime/attachments-8148fe66c0594e0b801598a7df0df08f`，代码没有再次移动。中文父目录的新 MySQL 实例在严格目录归属校验处被拒绝；只读 `HEX(@@datadir)` 证明 Windows MySQL 将 GBK 路径字节按 UTF-8 声明。失败实例已精确核验后正常关闭，保留证据。新环境改用 E 盘英文目录，未放宽 guard；最新清单由课程版 `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/current-fixture-path.txt` 指向。启动会话 holder81202 仍需保留，操作前重新核验运行身份。以下描述保留迁移时的停机状态，不是当前新环境的身份。

迁移前保存的四个 a19c PID 均已不存在，且没有进程命令行引用五个旧数据目录。原因未证实，不声称正常关闭。本次不启动服务，也不声称数据库恢复或业务验收通过。

E 盘 a19c 目录新增 `e-drive-stopped-fixture.json`，仅用于描述迁移后的路径，旧 PID/created 已清空，状态 `STOPPED_NOT_ACCEPTANCE_READY`。恢复前重新核对端口、离线 UUID、配置、可执行文件和凭据权限；启动后生成新的运行身份，再通过归属 guard 操作数据库或容器。历史 `fixture.json` / `resume-fixture.json` 及 C 盘硬编码恢复脚本只保留为证据，不直接运行。

后续构建快照和项目临时目录显式放在 E 盘课程版 `.superpowers/build-snapshots` 等忽略目录。不要更改全局 TEMP、PATH 或教师环境。旧 C 盘临时构建快照不是接续开发目录，可从 E 盘 WIP 重建独占快照。

迁移没有提交、推送、合并或新增业务功能；附件 Task2/4/7 及后续实机验收、全系统剩余范围仍按交接计划推进。
