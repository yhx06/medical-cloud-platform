# 医疗云项目：附件切片完成并入库后的交接（2026-10-15c）

更新：2026-10-15，Asia/Shanghai。本文取代 [2026-10-15b 交接](AI-HANDOFF-2026-10-15b.md) 的阶段状态。

**结论：附件切片（Tasks 1–9 + 5 + 6 + 8 验收驱动）已实现、单测、真实 MinIO、实机 HTTP 四层证据齐备；已推送并合并到 `origin/master`（f1b2f60）。用户已明确：本轮之后不再继续后续功能开发，等下一轮指令。全系统剩余约 7~9 项功能、2 项范围裁决、非功能证据与期末材料均未完成；浏览器与故障注入类实机门仍未验。不得把本交接当作全系统完成证明。**

## 1. 接手先做什么

1. 进入权威目录 `E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，核对分支 `codex/course-environment`、`git rev-parse HEAD`、`git status` 与 AGENTS.md；顶层旧 Spring/Vue 路线不是开发目录。
2. 读本地 SDD：`task-2-fix-report.md`、`task-2-fix-review.md`、`task-5-report.md`、`task-6-report.md`、`docs/course-labs/2026-10-12-attachments-acceptance.md`（实机验收）；再看 `progress.md` 最新几节。
3. **先向用户汇报/等待指令再开发**——用户上一条指令是“开发完这一部分就不用继续开发后面内容了，到时汇报一下内容”，汇报已完成。下一步做什么（剩余功能、范围裁决、期末材料）由用户决定。
4. 若继续实机工作：所有运行夹具现已停止。需要实测时用 `course/deploy/scripts/new-attachments-fixture.ps1` 从零建新专属夹具（前缀已改为设计口径 `course-attachments/`），生成器只建库不导表——随后 `setup-database.ps1`（schema+seed）与 `upgrade-attachments-database.ps1`（附件迁移）必做；部署 WAR 需带 `MEDICAL_COURSE_JWT_SECRET`、`MEDICAL_COURSE_DB_USER/PASSWORD`、`COURSE_ATTACHMENTS_*`、`COURSE_S3_*`（PREFIX=course-attachments/）环境变量重启容器，并用 `configure-wildfly.ps1` 注册数据源（注意第 6 节的工具缺陷）。

## 2. 权威工作树、提交与推送合并事实

- 权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`（已有 linked worktree，不要新建）。
- 远端：`origin = https://github.com/yhx06/medical-cloud-platform.git`（Git 代理 `http://127.0.0.1:7890` 已配置，勿反复改全局网络）。
- **已核验**：`HEAD == origin/codex/course-environment == origin/master == f1b2f60`（快进合并，非并行提交）；`git branch -f master HEAD` 本地已同步。大推送需要 `-c http.postBuffer=524288000`，SSL 偶发中断属代理波动，重试即可。
- 本轮提交链（全部已推）：`f179263` fix: close attachment storage review findings I1-I4；`3172eea` fix: reclaim in-flight storage calls at the deadline（复审 N1）；`4abf9bf` docs: hand off closed storage findings；`cfc5479` feat: coordinate attachment recovery and cleanup（Task6）；`3b125f9` feat: expose safe attachment api routes（Task5）；`299e26f` docs: record Task5/6 completion；`f1b2f60` test: verify attachment services in isolated runtime（Task8）。
- 保留未跟踪 `docs/course-architecture/Medical Cloud API Probe.eam`；`.worktrees/complete-system` 的 `feature/complete-system-implementation`（曾有 16 项无关改动）未触碰，合并前若涉及须重新核实并保护。

## 3. 切片状态

| 切片 | 事实 | 边界 |
| --- | --- | --- |
| Task1 契约/私有SDK | 早期已完成（`4efd4dd`） | 安全基线风险保留，不重试废弃类加载方案 |
| Task3 迁移 | `b97175d`，真实 MySQL 保护迁移已过 | 教师 5.6 未验 |
| Task9 夹具工具 | 完成；生成器前缀已修正为 `course-attachments/` | PS5.1 缺陷见第 6 节 |
| Task2 存储/验证/配置 | I1–I4/M1 修复 + 独立复审 Approved（I4 的 N1 残差已闭环）；真实 MinIO 门 17/17 ×2 个从零环境（含真实 300 秒到期） | 浏览器/容器未验 |
| Task4 状态机/授权/审计 | 早期已完成（`9e4c634`） | JTA 留 Task8 完整版 |
| Task6 编排/回收 | `cfc5479`，15 项定向测试；未知提交不猜删、独立失败审计、墓碑/围栏/退避 1/2/4/8/16/30、批≤100、枚举不删未登记键 | WildFly JTA/真实故障收敛未验 |
| Task5 八路由 API | `3b125f9`，19 项定向测试；鉴权先于分页/内容、200/202 删除、下载 no-store 不入列表 | 真实容器路由留验收 |
| Task7 门户 | 早期已完成（`8293961`） | 浏览器未验 |
| Task8 验收 | `f1b2f60`：verify-attachments.ps1 **7/7 实机门**（全新夹具真实 HTTP/MySQL/MinIO）+ 契约 12 断言；报告 `docs/course-labs/2026-10-12-attachments-acceptance.md` | 未验项见第 5 节 |

## 4. 实机验收发现并已修复（单测未抓到的）

1. **AttachmentReconciler 生产未接线**：@Singleton 由容器无参构造，`db`/`store` 从未注入，@Schedule 每分钟抛 `Cleanup transactions unavailable`。已补 `@EJB/@Inject` 懒接线；实机回收器随后正确把失败上传意图围栏为 ABORTED。
2. **对象键前缀不一致**：设计第 5 节对象键为 `course-attachments/<file_uuid>/<random_uuid>`，夹具生成器却用 `files/` 作桶前缀（桶策略也只授权 `files/*`），store 的 requireKey 拒绝一切对象键 → 上传 400 FILE_INVALID。已改生成器前缀为设计口径，并将实机桶策略/`COURSE_S3_PREFIX` 对齐到 `course-attachments/`。**后续所有夹具/环境必须用该前缀口径。**
3. 复审遗留 N1（迟到起跑的在途调用未绑定绝对截止）已在 `3172eea` 用 OkHttp EventListener 按操作取消闭环；配置侧 N2（策略前缀字符集）与 N3（测试注释）同步处置。

## 5. 证据分层与未验项（不得混淆）

- **已实现 / 单元通过**：PaaS 435 + Portal 106（`mvn clean verify`，0 失败，55 披露跳过为可选 MySQL/实机）；Task2/5/6 定向套件全绿。
- **真实 MinIO 通过**：17/17 含真实 300 秒到期（`task-2-fix-real-minio-gate*.log`）。
- **实机 HTTP 通过**：健康、两类上传（患者 JPG/PNG；医生 PDF 追加在单测层）、分页/按 ID 恢复、限额 429、矩阵（匿名 401/他院 403 先于参数解析、外来 ID 全拒）、审计脱敏、私有桶与签名（匿名 403/签名可读/篡改 403）+ 3 契约场景。
- **未验（逐项记明，勿冒充通过）**：Chrome/Edge 浏览器交互（正常/禁 JS 上传删除下载、键盘布局、401/403/413/415/429/503、超时恢复、医院冻结）；故障注入（发布审计回滚/提交响应丢失/清理失败/重启/迟到 PUT）；停 MinIO 核心预约→接诊→订单→支付链；Task3/4 真实 MySQL 迁移/并发命令；从零按 README 完整复现；医生 PDF 追加与 300 秒到期等待在验收驱动中未含。
- 本机 loopback HTTP ≠ 云端 HTTPS；MySQL 8.0.45 ≠ 教师 5.6；签名链接是五分钟持有者凭证，不承诺即时按人撤销。

## 6. 全系统剩余开发（权威清单 `docs/course-labs/2026-10-04-full-scope-inventory.md`）

| 类别 | 内容 |
| --- | --- |
| 功能（约 7~9 项） | FR-APT-05 患者病情补充；FR-CON-05 病历更正/版本（草稿+冻结后追加）；FR-ORD-04 未支付订单取消（撤销条件/病历保留/统计口径待设计）；平台新建医院、创建/停用医院管理员入口；医院资料/医生职称简介/本人资料视图；跨号源患者时间冲突规则；支付唯一流水号；完整分页/按 ID 恢复 |
| **需用户裁决** | ①药品处方：9 月 13 日设计含、需求书明确排除——不可擅自实施或删去；②P2 评价与 AI 三项是否纳入“所有预先设计功能” |
| 非功能证据 | 50 并发 p95≤2 秒性能报告；备份真实恢复演练；Chrome+Edge 全主流程；OpenAPI 覆盖验证；云端部署授权与实测（不可自行购买/开资源） |
| 期末材料 | 用例/组件/部署/时序/接口说明；最终报告；汇报 PPT；全功能演示脚本；备用环境；真实成员贡献（当前无已核实分配，不得编造） |

附件完成后若继续，顺序建议：先让用户裁决处方与 P2 范围 → 再做 P1 功能（可按 3 子代理并行 + 独立复审流程）→ 补非功能证据 → 期末材料。

## 7. 运行环境与证据位置

- `E:/medical-cloud-platform-runtime` 现有：`wildfly-stage-task8`（WildFly E 盘暂存副本，教师 D 盘安装未动）、三个已停夹具 `attachments-82ef...`/`0bd17...`/`8eaa3...`（数据保留）。更早的 8148 运行根曾整体消失（原因未证实），重建父目录时的记录在 `progress.md`。
- 全部证据/凭据/签名记录在 `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/`（Git 忽略，不随 push）。实机验收摘要 `task8-evidence/attachments-verification-summary.json`；契约证据 `task8-contract-evidence/`。
- 凭据在各夹具根的 `credentials.json`/`mysql-client.cnf`（ACL 受限）；**不得输出内容、密码、access/secret、完整签名 URL，也不得放 Git 或聊天**。
- 每次 SQL/存储写入、部署、停止前执行 `course/deploy/tests/attachments-fixture-ownership.ps1` 的 `Assert-OwnedAttachmentsFixture`；停止用 `Stop-OwnedAttachmentsFixture` + `Wait-OwnedAttachmentsFixtureStopped`。Windows PS 读 JSON 用 `[IO.File]::ReadAllText`；嵌套 powershell 传中文路径须用 `-EncodedCommand`（UTF-16LE base64），`-File` 会乱码。

## 8. 工具、纪律与安全

- 工具：Maven `D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`；构建 JAVA_HOME `D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`；容器 Java `D:/Java/jdk1.8.0_341`；MySQL `D:\MySQL\bin`；Tomcat `D:\apache-tomcat-9.0.24`；WildFly 源 `D:\wildfly-10.0.0.Final`（运行用 E 盘暂存副本）；Connector/J 8.0.33 在本地 Maven 仓库；MinIO/mc 在 SDD `tools-source-build-20261012/`。只设单命令环境，临时目录放 E 盘。
- `configure-wildfly.ps1` 在 PowerShell 5.1 会因 JVM 的 `Picked up JAVA_TOOL_OPTIONS` stderr 横幅（EAP=Stop）中断——用 `cmd /c "jboss-cli ... >out 2>err"` 等效执行，或后续修复该脚本；data-source 用 `wildfly-datasource.cli` 模板时**无 BOM 写入**（`[IO.File]::WriteAllText(...,[Text.UTF8Encoding]::new($false))`），否则 jboss-cli 报 invalid operation name。
- root 唯一操作 Git index/提交/推送；实现者只写独占文件；审查者不实现、不派子代理；问题交回同一实现者；TDD 与独立复审；测试用例与实现要能区分“单元/实机/浏览器”证据层级。
- 严格保护教师安装、其他 MySQL/MinIO 与 `C:/Users/yhx/.codex/task8-runtime-20260928`；禁止全局强杀、git reset --hard、checkout --、DROP 恢复、递归清理。只使用虚构/脱敏数据；不伪造成员贡献、云端部署或教师环境验证。
- 推送/合并前核工作树、远端 SHA、目标分支、脏树；大推送加 `http.postBuffer`；SSL 偶发断开重试即可，勿改全局代理。
