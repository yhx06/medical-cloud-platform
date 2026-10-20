# 医疗云：下一位 AI 接续入口（2026-10-20）

**Demo交付修复收尾：** 本轮主代理复测环境助手50、升级器65+67、驱动第一阶段53项均通过，相关小修复和Demo文档已独立复审。固定`40ebcfb`的Java8 `clean verify`通过（645执行、97可选跳过），双WAR已构建，门户脚本7/7通过。README/指南提交`773c151`已核验在GitHub课程分支；最终工具提交与包版本以Git记录为准。**没有创建/启动新的007实机环境，没有完成完整H03或全部系统验收。** 后续优先从新环境与Demo串行流程验证、核心竞态及门户缺陷，再继续全部后续任务；不要重做已关闭的小修复或误用旧005环境。

**最新用户优先级：先提交基本Demo。** 先完成当前小修复、[课程版README](../README.md)、[启动与演示指南](DEMO.md)、构建及安全推送；高级H03实机门和其他缺口继续保留，不冒充完整验收。早先用量中断后作者已恢复：主代理当前冻结源码离线驱动36/36、环境助手50/50通过；006/007兼容修复已通过规格/质量复审，最终复测与提交状态见[最新检查点](reviews/2026-10-20-repair-checkpoint.md)。

**最新修复检查点：** 见[中断状态及精确恢复顺序](reviews/2026-10-20-repair-checkpoint.md)。006/007离线53+55通过但未独立审查；H03/生命周期作者遇用量限制中断，工作树分别仍有20/8项离线失败。旧HEAD回归通过不覆盖这些未提交修改。不要部署或执行H03，按检查点恢复作者和审查。

**原AI接续审查增量（2026-10-20）：当前暂不可执行H03。** 已发现驱动消费/SQL目标/结果误报及缺少实际JTA恢复门，另有006/007迁移保护、预约/资料竞态和门户分页问题。详见 [接续审查与执行前阻塞](reviews/2026-10-20-h03-pre-execution-review.md)。下方“已完成/已通过”是前次会话报告，不能替代本次审查或扩展为整体验收通过；修复和新实机门尚未关闭。

本文是当前接续入口。先读本文，再按 [后续任务清单](DEVELOPMENT-BACKLOG-2026-10-19.md) 执行。旧交接、实验记录保留为历史证据，不追溯改写。**系统尚未全部完成，医院开通本批与整体验收均未完全闭合。**

## 1. 唯一工作位置和技术路线

本机项目根：`E:/云服务工程/云服务项目-医疗云`。

课程权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`，分支 `codex/course-environment`。顶层 `backend/`、`frontend/` 是旧 Java21/Spring/Vue 路线，不在那里开发课程版。不要机械创建重复的 `.worktrees/course-environment` 嵌套。

| 层 | 当前课程实现 |
| --- | --- |
| 共享契约 | `course/contract`：DTO、角色、业务校验和审计枚举 |
| SaaS 门户 | `course/portal`：Java8、Tomcat9、Servlet/JSP、原生表单和服务调用 |
| PaaS | `course/paas`：WildFly10、JAX-RS/RESTEasy、EJB/JTA、JPA/Hibernate、JNDI MySQL 数据源 |
| 私有附件 SDK | `course/attachment-sdk`：构建期隔离依赖，不是第三个部署服务 |
| 数据与存储 | MySQL；MinIO 私有桶/前缀，仅虚构课程数据 |
| 部署及验证 | `course/deploy`：SQL、保护升级器、PowerShell5.1 脚本、专项测试 |

教师兼容目标仍为 Java8、Tomcat9、WildFly10。真实验收使用过 MySQL8.0.45，不等于教师 MySQL5.6 已验。不要改回 Spring/Vue、升级教师安装或引入新服务。

## 2. 本次会话新增（2026-10-20 下午，提交 `a94ef1b`，已推送）

**H03 四参数验收驱动已交付**，补上了此前 H03 的唯一缺口（此前仅真实 MySQL 32/32 通过，缺 PowerShell 验收驱动）：

- `course/deploy/scripts/verify-hospital-onboarding.ps1` — Task7 验收驱动，四参数 `FixturePath,EvidenceRoot,NewRunId,Execute`。
  - 默认（无 `-Execute`）：不执行 SQL/登录/claim，列 7 个必需门为 unverified，退出 4。
  - `-Execute`：读 owned fixture 上下文 → `CreateNew` 消费记录（拒绝重复消费）→ 运行 32 例 MySQL 清单 → 四平台 API 全角色矩阵（原子开通/幂等重放/冲突/列表投影/增补/启停/最后管理员保护/停用旧 JWT 拒绝）→ 运行后身份健康 → 安全错误扫描 → 严格摘要。任何必需门 missing/skip/unverified 都退出非 0。退出码 0/1/3/4。
- `course/deploy/tests/hospital-onboarding-acceptance.Tests.ps1` — 原生 PS5.1 离线测试，10 例全过、0 失败；double 仅外部 SQL/HTTP/Maven 边界。

**尚未完成**：该驱动尚未对真实 owned fixture 实际执行（需要运行中的 H02 环境）。真实执行时它会消费一个全新 fixture，跑 `MySqlHospitalAdministrationTest` 的 32 例 + 四 API + EJB/JTA，并生成 `EvidenceRoot/<newRunId>-hospital-onboarding-{consumption,summary}.json`。

## 3. 全量状态矩阵（截至 2026-10-20 下午）

| 项 | 状态 | 提交 |
| --- | --- | --- |
| H01 超时回归 | 已闭合 | `4ad7d33` |
| H02 环境创建 + 正常停启 | 已实测 | `8711b65`..`10d2528` |
| H03 真实 MySQL 32/32 | 已通过 | `927a4bb` |
| H03 四参数验收驱动 | 已交付（未实机执行驱动） | `a94ef1b` |
| H04 双浏览器 24/24 + 禁JS/390px/日志扫描 | 已通过 | — |
| H04 余项（Task8 历史 opaque ID、停用会话退出、整分支审查、门 6） | 未闭合 | — |
| R03 最小可写资料 | 已完成 | `a97f01f` |
| R04 跨院半开区间冲突 | 已完成 | `fb837b6` |
| R05 唯一支付流水 | 已完成 | `cef540b`+`032f08c` |
| R06 完整分页 | 已完成 | `8e0388d`+`f3e22ac` |
| R07 处方/评价/AI 范围裁决 | 已完成（仅文档） | `54ccb63` |
| R08 OpenAPI + 运维追踪 | 已完成 | `3267cb7` |
| R01 诊疗增强真实验收 | 未闭合 | — |
| R02 附件真实验收 | 未闭合 | — |
| R09 性能/安全矩阵 + 双浏览器 | 未开始 | — |
| R10 备份恢复 + 从零复现 | 未开始 | — |
| R11 期末报告/PPT/演示 | 未开始 | — |
| R12 最终整合 + 交回 review | 未开始 | — |

远程 `origin/codex/course-environment` 已同步到 `a94ef1b`；工作树仅受保护未跟踪文件 `docs/course-architecture/Medical Cloud API Probe.eam`（保留，不提交）。

## 4. 剩余任务（按优先级，详细见 backlog）

1. **H03 收尾**：用新 32hex 编号、新 RuntimeRoot 实际运行 `verify-hospital-onboarding.ps1 -Execute`，闭环 32 例 + 四 API + EJB/JTA 审计失败回滚 + 12 表逐字段内存比较 + 锁释放 + 精确临时触发器恢复；补真实迁移反例（重复只读/异构/部分 DDL 拒绝/备份失败先于 DDL/旧升级器拒 005），MySQL8 与教师 5.6 分开记录。
2. **H04 余项**：Task8 的历史 opaque ID 可操作、停用账号会话退出、旧 JWT 失效、整分支独立审查、门 6 闭合。
3. **R01/R02**：诊疗增强与附件的真实验收闭合（附件 19 必需门中历史 7 通过/12 未验证；诊疗 HTTP 23/53、30 未验证），需先确认旧临床十例运行助手完整预检重构方案（Fix4 未批准）。
4. **R09–R12**：性能/安全矩阵、真实备份恢复与从零复现、期末交付物、最终整合 review。

## 5. 关键规则（不可回退）

- 项目新产物只放项目根 `.local/tests`、`.local/builds`、`.local/runtime`、`.local/evidence`；为测试设独立 TEMP/TMP/java.io.tmpdir，finally 恢复。不散落文件到 E 盘根/C 盘用户目录/系统临时目录。
- 保留其他工作树、用户改动、历史失败记录、受保护 EAM。不做广泛清理、不硬重置、不删除失败数据。
- 只有协调主代理操作共享 Git index、真实服务、SQL、实际 API、故障注入及凭据；真实环境变更串行。
- 保护教师 D 盘安装和端口 3318/8180/8188/10090。不制作 PowerDesigner 图、不启动 70 台服务器。
- 凭据、JWT、数据库、临床正文、原始日志、行快照/摘要不上传 Git。不在聊天/命令参数/报告输出密码。
- 不降低安全上限换取全绿；不跳过旧失败、不把 H2/RESOURCE_LOCAL 冒充 WildFly JTA、不把 MySQL8 冒充 5.6、不把 IAB/静态 JSP 冒充 Chrome/Edge。
- 原病历/订单冻结、更正历史每页最多 5 条、草稿私有、处方排除、评价/AI 可选未实施等已确认规则不回退。
- Git 已配本机代理 `http://127.0.0.1:7890`，不要重复修改全局配置；不 force push、不关闭 TLS。

## 6. 构建与测试命令

不含真实凭据的构建入口（Java8 + 项目内隔离源码副本执行）：

```powershell
mvn -o -f course/pom.xml -Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final clean verify
```

真实 MySQL 32 例（需 `COURSE_ONBOARDING_MYSQL_*` 环境变量指向专属库）：

```powershell
mvn -o -f course/pom.xml -Pmysql-integration -pl paas -am -Dtest=MySqlHospitalAdministrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

本机 Maven：`D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`；JDK8：`D:/Java/jdk1.8.0_341`；WildFly：`D:/wildfly-10.0.0.Final`。离线 `-o` 缺依赖应报告并通过正常权限补依赖，不能把缺依赖当业务 RED。

H03 离线测试（原生 PS5.1，double 仅外部边界）：

```powershell
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File course/deploy/tests/hospital-onboarding-acceptance.Tests.ps1
```

## 7. 给下一位 AI 的第一轮行动

1. 读本文、任务清单、`AGENTS.md`；核对 Git 分支/提交/未跟踪文件，保护原改动与 EAM。
2. 确认本机没有遗留实际操作窗口；H02 运行环境若已因重启停止，先对账 owner/PID/监听/UUID，不直接重放已消费的启动器。
3. 按优先级完成 H03 驱动实机执行 → H04 余项 → R01/R02 → R09–R12；未确认的新业务先设计、确认后再实施。
4. 每个批次写清真实执行、可选 skip、mandatory 未验及恢复状态；不自动合并或宣称全系统完成。

交回用户时给出 backlog R12 所列 review 返回包（baseline/最终 commit、状态矩阵、可执行命令与 native 退出、EJB/JTA/浏览器/性能/恢复证据、deferred 项与高风险文件索引）。
