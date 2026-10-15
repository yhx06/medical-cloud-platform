# 医疗云项目：Task2 存储整改后交接（2026-10-15b）

更新：2026-10-15，Asia/Shanghai。本文取代 [2026-10-15 交接](AI-HANDOFF-2026-10-15.md) 的“存储切片审查未通过/I1–I4 未修”状态；其余全系统范围与纪律继续有效。

**结论：Task2 存储切片的 I1–I4/M1/生成器权限缺口已修复、独立复审质量 Approved（无新 Critical/Important）、并经真实 MinIO 门在两个从零环境复现。Task6 编排/回收与 Task5 八路由 API 已实现并联合 GREEN（435 项 PaaS 回归 0 失败）。Task8 实机/浏览器验收与全系统剩余范围未完成。不得据此宣称附件全部完成或系统完成；无推送、无合并。**

## 1. 接手先做什么

1. 进入权威目录 `E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，核对分支 `codex/course-environment`、HEAD、Git status 与 AGENTS.md；不在顶层旧 Spring/Vue 路线开发。
2. 读本地 SDD：`task-2-fix-report.md`（实施+证据+边界）、`task-2-fix-review.md`（独立复审裁决）、`progress.md` 最新两节。历史 ledger 的“未修/在跑”描述已过期。
3. 下一步按已批准计划推进 **Task6 编排与 Task5 API**（此前门已通过），再 Task8 实机/浏览器验收，之后全系统剩余范围（见第5节）。
4. 运行夹具现均停止。需要实测时先安全恢复或新建独立环境生成新身份，绝不直接使用保存的 PID；每次 SQL/存储写入/部署/停止前执行 `course/deploy/tests/attachments-fixture-ownership.ps1` 的 `Assert-OwnedAttachmentsFixture`。

## 2. 权威工作树、提交与授权

- 权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`（已有 linked worktree，不要新建）。
- 本轮提交（本地，未推送）：`f179263` fix(course): close attachment storage review findings I1-I4；`3172eea` fix(course): reclaim in-flight storage calls at the deadline；`cfc5479` feat(course): coordinate attachment recovery and cleanup（Task6）；`3b125f9` feat(course): expose safe attachment api routes（Task5）。接手以 `git rev-parse HEAD` 为准。
- 授权沿用：附件设计/计划/多智能体并行/本地 Git 提交，验证完成后的推送合并授权仍在但**门未全过不推不并**。私有仓库 `yhx06/medical-cloud-platform`。Git 全局代理 `http://127.0.0.1:7890` 不反复改。
- 保留未跟踪 `docs/course-architecture/Medical Cloud API Probe.eam` 与另一会话加在 `docs/AI-HANDOFF-2026-10-15.md` 的演示注记（其演示环境已不存在，该注记仅历史）。不清理、不顺手暂存。

## 3. Task2 修复后的事实（与证据）

| 发现 | 状态 | 要点 |
| --- | --- | --- |
| I1 匿名策略绕过/假阳性负例 | FIXED | Jackson 结构化逐 statement 检查，字符串/对象/数组/转义/NotPrincipal/未知 Effect/坏 JSON 全失败关闭；假服务按 query key 分派（SDK 发 `versioning=` 等），每负例断言到达目标分支+私有桶 DELETE 正控+Deny 不误判 |
| I2 临时文件归属竞态 | FIXED | `TempFileHandoff` 显式协议 offer/accept/abandon；完成被放弃仍清理，accept 后不误删；barrier 双向序压力 + 超时清理集成用例 |
| I3 listKeys 越限翻页 | FIXED | 显式 Iterator 到 limit 即停；二页必失败假服务证明 limit=1 不请求第二页 |
| I4 超时不回收底层调用 | FIXED（复审后补强） | OkHttp `callTimeout`=store deadline + 绝对截止前每 SDK 调用 `checkDeadline` + `CallTracker`/`TrackingListener` 在外层超时同步取消本操作在途 HTTP 调用（实测 callStart 在调用方线程，ThreadLocal 归属） |
| M1 签名时刻/到期时间 | FIXED | `expiresAt = X-Amz-Date 签名时刻 + TTL`（设计“签名生成时刻加300秒”），慢预检一致性用例，真实门断言同步 |
| 生成器三项元数据权限 | FIXED | 共享 `Get-AttachmentsStoragePolicy`：精确桶 ARN 上 GetBucketVersioning/GetBucketObjectLockConfiguration/GetBucketPolicy，保留 prefix List 条件与对象前缀范围；结构化行为回归 40 断言 |

证据（`.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/`）：`task-2-fix-red.log`、`task-2-fix-i4-discriminate-old-code.log`（旧代码真实失败的区分力）、`task-2-fix-green.log`、`task-2-fix-paas-default-regression.log`（401/0/55披露跳过）、`task-2-fix-real-minio-gate.log`（17/17、315.3s、含真实303秒到期；复审曾误读为截断，实为完整终局）、`task-2-fix-followup-green.log`、`task-2-fix-followup-paas-regression.log`、`task-2-fix-real-minio-gate-followup.log`（17/17、315.4s，第二个从零夹具）。

**运行环境事实**：接手时 `E:/medical-cloud-platform-runtime` 整体消失（旧 8148 夹具数据/凭据全无，原因未证实，不写成主动清理）。已重建 ASCII 父目录，并用修复后的生成器从零创建两个专属夹具 `attachments-82ef016770724f64828d5403ed40cf63`、`attachments-0bd17a38abe14fb68834dfcb33a124ff`（证据在 SDD `fixtures/<ownerId>/`）；两者均四组件归属通过、实机门通过后 `Stop-OwnedAttachmentsFixture` 正常停止并验证进程/监听消失。生成器写出的 `storage-policy.json` 从零即含三项元数据读权限——**部署夹具权限复现门已过，无手工打补丁**。

## 4. 仍须遵守的边界

- 现有证据是 Java8/假 HTTP/真实 MinIO(loopback)/RESOURCE_LOCAL 层面；**不是**教师 Tomcat 9.0.24/WildFly 10 CDI/JTA、浏览器、云端 HTTPS 证据。MySQL8 不能称教师 5.6 已验，本机 HTTP 不能称云端 HTTPS 已验。
- M1 采用设计“签名时刻+TTL”口径，公共接口 `signGet(file,issuedAt,ttl)` 未改；若裁决改 issuedAt 锚定属一行调整。
- 复审 Minor N2/N3/N4 已在 `3172eea`/报告中处置：前缀字符集收紧、测试注释、RED 留证说明。
- 五分钟签名是持有者凭证，不承诺即时按人撤销；URL 不进列表/session/flash/Location/日志；签发审计不表示下载完成。业务规则（BOOKED/IN_PROGRESS 上传、医生仅追加、医院停用只拒新写、管理员不下钻、uploadId=fileId、10MiB/JPG/PNG/PDF、20000000 像素/200 页）不变。

## 5. 下一阶段（按已批准计划）

1. ~~**Task6 编排/回收**~~ 已完成（`cfc5479`）：NOT_SUPPORTED 编排、认领/真实校验/PUT 后重认证、短事务发布、未知提交先新事务查库（AVAILABLE 保留、库不可读不删除不猜审计）、独立失败审计、持久墓碑/围栏/退避/枚举告警。15 项定向测试通过；WildFly JTA 与真实故障收敛仍留 Task8。
2. ~~**Task5 API**~~ 已完成（`3b125f9`）：八路由、鉴权/父授权先于分页与内容、稳定错误码与封闭原因、200/202 删除语义、下载 no-store 不入列表。19 项定向测试 + 联合回归 435/0 失败。
3. **Task8**：实际双容器/CDI/JTA 代理、数据源 RR、两类三格式上传、替换 ID/角色/租户矩阵、并发回滚、提交响应丢失、迟到 PUT/重启/删除失败、真实签名/脱敏日志、Chrome/Edge 及无 JS、从零环境复现、停 MinIO 核心链仍成功。必需门 skip 必须失败退出。
4. **全系统剩余**（权威清单 `docs/course-labs/2026-10-04-full-scope-inventory.md`）：病情补充、病历草稿/更正、医院及管理员开通/状态、人员资料、跨号源患者时间冲突、支付流水唯一性、订单取消语义、完整分页/按 ID 恢复；处方与原需求排除冲突、可选评价/AI 按已批准材料核对；另有 50 并发/p95≤2秒、备份真实恢复、API/OpenAPI 与设计说明、云资源/HTTPS 授权、完整浏览器主流程、期末报告/PPT/演示/备用环境/真实成员贡献。

## 6. 工具与协作纪律

- Maven `D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`；构建 JAVA_HOME `D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`；容器 Java `D:/Java/jdk1.8.0_341`；MySQL `D:\MySQL\bin`；Tomcat `D:\apache-tomcat-9.0.24`；WildFly `D:\wildfly-10.0.0.Final`；MinIO/mc 在 SDD `tools-source-build-20261012/`（固定官方源码构建 DEVELOPMENT.GOGET，归档/无维护/AGPL/仅虚构 loopback 验收限制须披露）。只设单命令环境，临时目录/快照放 E 盘，不改全局 PATH/JAVA_HOME。默认回归前 unset 可选 COURSE_MYSQL/fixture 环境。
- root 唯一操作 Git index/提交；实现者只写独占文件，测试用冻结已提交 course 源码加自己 overlay 的 E 盘独立快照；审查者不实现、不派子代理；问题交回同一实现者。跨会话句柄不可用时单一接管者读现有 WIP/报告，不重写、不复活多个写入者。
- 严格保护教师安装与其他数据库/存储；禁止全局强杀、git reset --hard、checkout --、DROP 恢复、递归清理。只使用虚构/脱敏数据。
- `.superpowers` 为本地证据不入 Git；不得提交凭据、签名 URL、运行目录。推送/合并前重新核远端、目标分支、脏树、远端 SHA，并完成相应实机门与全分支审查。
