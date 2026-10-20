# 接续入口：医院开通切片完成后的全系统剩余开发（2026-10-20）

> 本文件接续 `AI-HANDOFF-2026-10-19.md` 与 `DEVELOPMENT-BACKLOG-2026-10-19.md`。医院开通切片的实现、真实环境与双浏览器证据已闭合；全系统仍未完成。不要重建项目、不要切回旧 Spring/Vue、不要删除历史失败证据。

## 1. 工作树与提交

- 权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`
- 分支：`codex/course-environment`（私有仓库 `yhx06/medical-cloud-platform`）
- 交接基线：`afd14fa761d46b302ea4b36f5a03141a0e84e9ba`
- 医院切片提交：
  - `4ad7d33` H01 附件期限回归
  - `8711b65` 独立环境创建器（WAR 选择、私有 MySQL 模块、构建门、生命周期）
  - `a326080` Windows loopback 启动修复
  - `69e81f4` 保留 WildFly 默认数据源
  - `58c7ebf` 注入 PaaS 签名密钥
  - `44c896c` 账户凭据持久化
  - `10d2528` 账户数组解析（最终成功 `-Start` 使用的创建器版本）
  - `927a4bb` 真实 MySQL 管理测试强化
- 未跟踪保护：`docs/course-architecture/Medical Cloud API Probe.eam`，不得删除或提交。

## 2. 已完成的医院切片

| 项 | 状态 | 证据 |
| --- | --- | --- |
| H01 MinIO 并发截止时间 + Portal 二进制总期限 | 完成 | `.superpowers/sdd/2026-10-18-medical-cloud-hospital-onboarding/h01-report.md`；`4ad7d33`；全量 723 项构建 |
| H02 新独立 005 环境创建 + 正常停启 | 完成 | `h02-report.md`；RunId `a5a164ca505e4ee5aebd60412da48522`；`root-stop-start-verified` |
| H03 真实 MySQL 32 项 | 完成 | `h03-report.md`；32/32、0 skip、26/26 锁标签；`927a4bb` |
| H04 双浏览器/禁 JS/390px/日志泄密 | 主路径完成，Task 8 门 1/5/6 未闭合 | `docs/course-labs/2026-10-18-hospital-onboarding-acceptance.md`；`.local/evidence/h04-f093922-chrome/chrome`、`h04-f093933-msedge/msedge` |
| 全量 clean verify | 完成 | 723 发现 / 626 执行 / 97 可选跳过 / 0 失败错误 / native 0 |

浏览器驱动是 Git 忽略的本机证据工具：`.local/tests/h04/browser-acceptance.js`，依赖 `.local/browser/node_modules/playwright-core`，只驱动已安装 Chrome/Edge，不下载浏览器。它不是产品依赖。

## 3. 当前运行环境（本机私有）

- 运行根：`.local/runtime/attachments-a5a164ca505e4ee5aebd60412da48522`
- 源码：`44c896ca65e442c5726db95efd5e99465503c8f9`（生产源码与 HEAD 相同；`44c896c..HEAD` 只有创建器账户解析 `10d2528` 与测试 `927a4bb`，无 `src/main` 变更）
- 状态：五组件 running，schema 005，主/辅 MySQL 各 13 表，Portal/PaaS/MinIO 健康 200
- 凭据：仅在该运行根的 `credentials.json`/`accounts.json`/`*-client.cnf`，私有 ACL；**不得**复制到文档、聊天、Git 或截图
- 已消费：该 RunId 的创建、两次正常停启、H03 专属测试库、H04 两组浏览器医院/管理员/科室。不要重放这些编号；新验收另建新 RunId 或新医院/账号
- 停止/启动：用创建器 `-Stop`/`-Start`；不要 force kill，不要 DROP，不要操作 `3318/8180/8188/10090`

## 4. H04 后仍未完成

### 4.0 Task 8 尚未闭合

- 历史 opaque tenantId 的真实旧医院行未在 owned 环境操作；只在离线门户测试验证查询编码。
- 停用账号后“现有门户会话下一次请求跳登录”未单独测试；已验证同一 JWT 对 API 401。
- 整分支 Spec §1–10 审查未完成；私有 `.superpowers/.../task-8-report.md` 只是本轮草稿。
- 验收记录、交接与 README 增量尚未提交。

### 4.1 H03 余项（真实 EJB/JTA 与验收驱动）

- `verify-hospital-onboarding.ps1` 与 `hospital-onboarding-acceptance.Tests.ps1` 尚未创建。需要四参数入口（`FixturePath,EvidenceRoot,NewRunId,Execute`）、默认退出 4、CreateNew 消费、缺失/重复/身份不符/写前意图/故障触发器恢复检查。
- 真实 EJB/JTA 审计失败注入、独立 FAILED、锁释放、12 张非审计表逐行 NULL/HEX 内存比较未做；当前 32 项是真实 MySQL，但不是容器 JTA 故障。
- 四个 API 的全角色/冻结/旧 JWT 矩阵只在浏览器切片覆盖了主要路径，未做 API 级完整矩阵。
- 迁移反例（重复只读、部分 DDL、备份失败先于 DDL、旧升级器拒 005）尚未全部实机执行。

### 4.2 R01 诊疗

53 门中仅部分历史记录。**旧临床十例运行助手的完整预检重构（Fix4）未单独批准**，不能直接改。需要先向用户说明方案并获批，再解决源码导出/参数作用域/短路径/换行 pin，使用新编号。补充/草稿/更正已实现，但 Chrome/Edge、禁 JS、CSRF、409 保留输入、停 MinIO 独立性、真实 JTA 审计失败仍未验。

### 4.3 R02 附件

19 门中历史 7 通过/12 未验证。H01 只修了两个超时回归，不等于附件整体验收。仍需：真实 PDF/300 秒到期/停 MinIO、跨院/跨患者/非原医生、冻结/停用、真实内容与伪扩展名、大小/像素/页数、并发上传/重复 content/删除冲突、私有 URL 匿名 LIST/GET、完整浏览器与从零复现。

### 4.4 需要用户先裁决的业务（不能自行扩大范围）

- **R03 资料**：医院/医生/本人资料字段、可见性、审核/通知范围未定。
- **R04 预约冲突**：冲突定义（患者/状态/跨院/端点/时区/取消释放）未定；不能直接把半开区间方案当批准。
- **R05 订单取消**：订单在完成接诊后产生，取消是否撤销接诊、账单/统计口径未定；支付唯一流水格式/生成方未定。
- **R07 处方/P2**：需求书排除结构化处方，旧设计含处方；FR-FBK-01 与 FR-AI-01～04 为 P2 可选，需逐项裁决。
- **R09/R10**：真实公网压力测试、云端资源、MySQL 5.6 环境需要用户授权/资源；未授权不购买、不开公网。

### 4.5 可先做且不需要新业务裁决

- R06 分页：盘点无界/截断列表，补稳定唯一排序、总数/页/size 上界、空页、按 ID 受权恢复；不得放宽更正 5 条或附件既有分页。
- R08 OpenAPI：为当前公开 route 生成 `docs/api/course-openapi.yaml`，自动校验 route/DTO/业务码/request-ID/二进制上传/私有下载；不引入 Java 21 工具。
- R02 部分验收：可在当前 owned 环境用新虚构患者/预约/附件执行，但必须先准备新目标，不重放旧编号。
- R11 文档/PPT：需要老师实际格式、页数、时长、成员贡献；未提供前不编造。

## 5. 建议顺序

1. 先做 R02 附件真实验收（现有环境、新目标），再补 R01 已批准部分；Fix4 先向用户确认。
2. 并行做 R06 分页与 R08 OpenAPI（独立文件），每批测试 + 独立审查。
3. 向用户一次性收集 R03/R04/R05/R07 裁决，再实施对应切片。
4. 最后做 R09 性能/安全矩阵、R10 恢复与 5.6、R11 期末材料、R12 整分支审查与合并。

## 6. 审查与提交规则

- 每个小批次：聚焦 RED/GREEN、全量或受影响回归、独立子代理审查、精确提交；记录命令、native 退出码、执行/失败/跳过数量。
- 不降低安全上限、不删断言、不反复重跑到绿、不把 skip 当通过。
- 不覆盖失败证据；新运行用新编号；实际服务/SQL/故障由主代理串行协调。
- 只提交安全源码与文档；凭据、JWT、临床正文、数据库、原始日志不进 Git/聊天。推送前核对远端 SHA，不 force。

## 7. 结论

医院开通切片可以进入整分支独立审查；全系统仍有 R01–R12 的实质缺口。下一步不应重复医院浏览器验收，而应从 R02 附件真实门或 R06/R08 的独立开发开始。
