# 医院开通与医院管理员：真实环境与双浏览器验收（2026-10-20）

本记录只覆盖医院开通切片（Task 6–8）。所有医院、账号、科室均为本轮虚构数据；记录不含密码、令牌、哈希或临床正文。**本切片的主路径已有真实证据，但 Task 8 的 H03 验收驱动、历史 opaque ID、停用会话退出与整分支审查仍未闭合，不能写成“医院切片全部完成”。**

## 1. 版本与环境

| 项目 | 值 |
| --- | --- |
| 权威工作树 | `.worktrees/course-environment`，分支 `codex/course-environment` |
| 交接基线 | `afd14fa761d46b302ea4b36f5a03141a0e84e9ba` |
| 医院切片提交 | `4ad7d33` H01、`8711b65` 创建器、`a326080` loopback 启动、`69e81f4` 默认数据源、`58c7ebf` JWT 密钥、`44c896c` 账户持久化、`10d2528` 账户数组解析、`927a4bb` MySQL 测试强化 |
| 运行环境源码 | `44c896ca65e442c5726db95efd5e99465503c8f9`（最终成功 `-Start` 使用 `10d2528` 的创建器；`44c896c` 之后主源码无变更） |
| 运行 RunId | `a5a164ca505e4ee5aebd60412da48522` |
| 数据库 | 主/辅两个独立 MySQL 8.0.45 实例，均为 `medical_cloud_course`、schema 005、13 张业务表 |
| 容器 | Tomcat 9.0.24（Portal）+ WildFly 10.0.0.Final（PaaS），私有两个 base 目录 |
| 对象存储 | 源码构建 MinIO + mc，私有 bucket/前缀，仅 loopback |
| 生命周期 | `running`，`lifecycleValidation=root-stop-start-verified`；两次完整正常停/启，最终 cycle 五组件新 PID |

`44c896c..HEAD` 的差异只有创建器账户数组修复（`10d2528`）与 MySQL 测试强化（`927a4bb`），没有 `src/main` 变更。当前 HEAD 构建的 WAR 与部署 WAR 逐 entry 比较：PaaS 130 项、Portal 57 项，差异仅为 CRLF/LF 与嵌套 JAR 时间戳，归一化后无主源码内容差异。

运行环境使用 `MEDICAL_COURSE_JWT_SECRET` 私有注入，凭据只在本机 Git 忽略目录，ACL 仅当前用户可写。验收前后未操作教师安装、`3318/8180/8188/10090`、旧临床/附件实例或历史证据。

## 2. 证据分层

| 层级 | 结果 | 不可外推的结论 |
| --- | --- | --- |
| 离线创建器行为 | **36/36 通过**，native exit 0 | 不等于真实 MySQL/容器启动 |
| 真实 MySQL 管理事务 | **32/32 执行、0 失败/错误/跳过**，26/26 具名锁标签 | 是 Surefire 套件，不是 H03 四参数验收驱动 |
| 真实环境创建 | 五组件 running、schema 005、两库 13 表、JPA `validate`、登录/门户/MinIO 200 | 不等于每个页面交互 |
| 正常停/启 | 两次完整 stop + start，manifest `root-stop-start-verified` | 不等于故障恢复或云端 |
| 双浏览器 | **Chrome 24/24、Edge 24/24**（见 §3） | 仅医院切片主路径，不含 R01/R02 全门 |
| 全量构建 | **723 项，626 执行，97 可选跳过，0 失败/错误** | 跳过项不计通过 |

## 3. 双浏览器矩阵（headless 真实内核）

浏览器为本机真实内核（Playwright 只驱动已安装浏览器，不下载替代内核；`%LOCALAPPDATA%\ms-playwright` 为空）：

| 浏览器 | 版本 | 结果 | 证据 |
| --- | --- | --- | --- |
| Google Chrome | 155.0.8059.80 | 24 PASS / 0 FAIL | `.local/evidence/h04-f093922-chrome/chrome/summary.json` |
| Microsoft Edge | 155.0.4283.62 | 24 PASS / 0 FAIL | `.local/evidence/h04-f093933-msedge/msedge/summary.json` |

每个浏览器独立新建医院、独立账号与独立 operationKey；桌面视口 1366×900，移动视口 390×844。逐项结果：

| # | 检查 | Chrome | Edge | 观察 |
| ---: | --- | --- | --- | --- |
| 1 | 平台管理员登录 | PASS | PASS | 路径必须为 `/portal/platform`，页面含“医院管理” |
| 2 | 开通表单有 operationKey、CSRF、4 个带 label 输入 | PASS | PASS | 浏览器原生 label 关联 |
| 3 | 缺失 CSRF | PASS | PASS | 403 |
| 4 | 重复参数 | PASS | PASS | 400 |
| 5 | 两次密码不一致 | PASS | PASS | **响应 400**；两个密码框清空，医院名/用户名/operationKey 保留 |
| 6 | 开通医院及首管理员 | PASS | PASS | 结果页显示医院、租户、管理员编号；无密码 |
| 7 | 结果页刷新 | PASS | PASS | 同一 URL/租户/管理员，不重复创建 |
| 8 | 同 operationKey + 原密码重放 | PASS | PASS | 303 到结果页，同一 tenant/admin（幂等） |
| 9 | 同 operationKey + 不同密码 | PASS | PASS | 409 `OPERATION_KEY_CONFLICT`，页面提示“操作编号与原提交内容冲突” |
| 10 | 最后一位启用管理员停用 | PASS | PASS | 服务端 409，错误文案含“最后一位启用的医院管理员”；列表仍 ACTIVE；按钮 `disabled` + `aria-disabled=true` |
| 11 | 增补第二管理员 | PASS | PASS | 同医院、不同管理员编号 |
| 12 | 首管理员停用/启用 | PASS | PASS | 两方向 303，列表状态同步 |
| 13 | 冻结前医院管理员可写 | PASS | PASS | 存在科室创建表单 |
| 14 | 停用医院 | PASS | PASS | 租户 DISABLED |
| 15 | 冻结医院管理员工作台 | PASS | PASS | 只读文案含“医院已停用”；科室/医生/号源三个写表单均不渲染；**API 直写返回 409**，刷新后无该科室 |
| 16 | 冻结期间增补管理员 | PASS | PASS | 创建成功，医院仍 DISABLED（增补不解冻） |
| 17 | 冻结期间停用/启用账号 | PASS | PASS | 两方向成功，医院仍 DISABLED |
| 18 | 启用医院后恢复写入 | PASS | PASS | 只读提示消失，成功创建科室并出现在列表 |
| 19 | 停用后旧 JWT | PASS | PASS | 停用前 `/hospital/context` 200，停用后同一 token 401 |
| 20 | 390px 布局 | PASS | PASS | `body=390, document=390, viewport=390`，无横向溢出 |
| 21 | 键盘 Tab 顺序 | PASS | PASS | `hospitalName → username → initialPassword → confirmInitialPassword` |
| 22 | 禁 JavaScript 原生表单 | PASS | PASS | `javaScriptEnabled=false` 下原生 POST 成功；含 `<em id="h04-injected">` 的名称按字面显示，注入元素不存在 |
| 23 | 运行根日志泄密扫描 | PASS | PASS | 扫描运行根 17 个 `.log/.out/.err/.txt`（Chrome 614,123 B；Edge 684,974 B），查原文/URL 编码/JSON 转义/UTF-16；无跳过、无超限 |
| 24 | 结果/管理员列表/平台页泄密扫描 | PASS | PASS | 三页均校验未跳登录；无密码、URL 编码密码、`$2a$`、`accessToken`、operationKey |

HTML 转义使用医院名 `H04仁和<em id="h04-injected">医院</em>&"测试-<hex>`：结果页与列表均按字面文本显示，`#h04-injected` 元素不存在；`<`、`&`、`"` 均未形成标签或属性。禁 JS 用例使用同类 payload。

“结果页最多保留 10 个 nonce”不在浏览器中重复创建 11 家医院，由 `PlatformAdministrationPortalTest.creationUsesSafePayloadAndIndependentSessionResultsWithRefreshAndEviction` 覆盖：同一 session 第 11 次创建后原 nonce 失效，且 session 属性中不含密码。该测试是离线门户证据，不冒充浏览器门。

Chrome 与 Edge 均为 Chromium 155，390px 截图像素可能相同；截图不作为“双引擎独立”证据，独立证据是两次独立的浏览器进程、会话、医院与断言执行。

驱动与摘要可追溯：

| 文件 | SHA-256 |
| --- | --- |
| `.local/tests/h04/browser-acceptance.js` | `aa23051e21a9b685b319c62f58649a0c1122bd36a87eaaba10a4b97ed69fafb0` |
| Chrome summary | `725546132089b30b9593b47caa4cca71ebc868e1c556a5e9fd0350bcabc54623` |
| Edge summary | `0dac0299968ceb5f1c5c818440d3c678e963b4423c6bbfdbb4f1ab7d3a280b95` |

## 4. 双容器与秘密边界

- 浏览器只访问 `http://127.0.0.1:<portal>/portal`，Portal 通过 `MEDICAL_COURSE_PAAS_URL` 调用 WildFly JAX-RS；数据经 EJB/JTA 落到两个独立 MySQL。
- 平台管理员列表查询仅选择 `id/tenantId/username/status`（`PlatformAdministrationService`），不读取目标 `password_hash`；结果页 DTO 只有医院名、租户、管理员编号与用户名。
- 初始密码只存在于本次 POST 与 BCrypt 哈希；结果页、平台列表、运行根日志与证据摘要均无密码。失败页清空两个密码框，保留非秘密字段与 operationKey。
- 浏览器进程未使用真实用户配置目录，未持久化密码；证据只保存截图与检查结果。

## 5. 运行命令（可复现）

```powershell
# 环境（本机私有路径，仅示例）
powershell.exe -NoProfile -File course/deploy/scripts/new-hospital-onboarding-fixture.ps1 `
  -RuntimeRoot '<仓库根>\.local\runtime\attachments-<32hex>' -MySqlBin 'D:\MySQL\bin' `
  -JavaHome 'D:\Java\jdk1.8.0_341' -TomcatHome 'D:\apache-tomcat-9.0.24' `
  -WildFlyHome 'D:\wildfly-10.0.0.Final' -MinioExecutable '<minio.exe>' -MinioClientExecutable '<mc.exe>' `
  -MavenExecutable '<mvn.cmd>' -ConnectorJar '<mysql-connector-j-8.0.33.jar>' `
  -SourceCommit '<40hex>' -NewRunId '<32hex>' -Execute

# 正常停/启（不需要 SourceCommit/Maven/ConnectorJar）
... -Stop
... -Start

# 浏览器验收（驱动与 playwright-core 在 Git 忽略的 .local，不随产品部署）
$env:NODE_PATH = '<仓库根>\.local\browser\node_modules'
$env:H04_RUN_ID = '<runid>'; $env:H04_CHANNEL = 'chrome'   # 或 msedge
node <仓库根>\.local\tests\h04\browser-acceptance.js
```

## 6. 全量构建与回归

在独立短路径源码副本执行：

```powershell
mvn -o -f course/pom.xml -Dcourse.test.wildfly.home=D:/wildfly-10.0.0.Final clean verify
```

| 模块 | 发现 | 失败 | 错误 | 跳过 | 执行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| contract | 21 | 0 | 0 | 0 | 21 |
| paas | 556 | 0 | 0 | 97 | 459 |
| portal | 146 | 0 | 0 | 0 | 146 |
| **合计** | **723** | **0** | **0** | **97** | **626** |

native 退出码为 0（`verify-exit.log` 与构建目录保留在 `.local/builds/h01-d4e5f60718293041`）。97 个跳过均为需要外部 MySQL/MinIO 的强制门，已在 H03 与 H04 中单独执行，不计入本次 626。构建使用 Java 8，未降级安全上限、未删除断言。

## 7. Task 8 六项状态（诚实口径）

| 门 | 状态 | 说明 |
| --- | --- | --- |
| 1. 真实浏览器 | **部分闭合** | 24 项双浏览器主路径通过；历史 opaque tenantId 真实行、停用账号现有门户会话被踢出未覆盖 |
| 2. 13 表/双容器/秘密边界 | **闭合** | 两库 13 表；日志/响应/HTML/session 边界见 §4 与 #23/#24 |
| 3. 全构建 | **闭合** | §6 |
| 4. 交付文档 | **本文件 + 交接 + README 已写，待提交** | 提交后闭合 |
| 5. 整分支独立审查 | **未闭合** | 本文件经过一次只读审查并已修正过度断言；Spec §1–10 映射与 declined 项裁决待 `task-8-report.md` |
| 6. 精确提交/推送 | **未执行** | 不 force、不覆盖他人改动 |

## 8. 仍存在的限制

1. 浏览器验收在 **headless** 真实 Chrome/Edge 上执行；未使用真实显示器的人工目视检查，键盘验证为 Tab 顺序而非全页面读屏。
2. 历史 opaque tenantId 的“可操作”只在离线门户测试中验证查询编码；当前 owned 环境没有非 UUID 的旧医院行，未做真实操作。
3. 停用账号后“现有门户会话下一次请求跳登录”未单独测试；已验证的是同一 JWT 对 API 直接返回 401。
4. `verify-hospital-onboarding.ps1` / `hospital-onboarding-acceptance.Tests.ps1` 尚未交付；H03 目前只有 32 项 Surefire 真实 MySQL 证据。
5. 本切片未覆盖 R01 诊疗 53 门、R02 附件 19 门、R04 预约冲突、R05 取消/流水、R06 分页、R07 处方/评价/AI、R09 性能与全功能矩阵、R10 备份恢复/MySQL 5.6、R11 期末材料。
6. 医院切片只在 loopback 课程环境验证，不是云端或生产部署；MinIO 为归档版本，WildFly 10/Java 8 为课程限制。
7. 本轮真实创建过程中保留的失败目录（代理变量、进程时间、双栈监听、ExampleDS、JWT 密钥、账户文件、数组解析）不删除，作为缺陷修复证据；不作为当前失败。

## 9. 结论

医院开通切片的主路径实现、真实 MySQL 管理事务、真实双容器、正常停启、双 headless 真实浏览器、禁 JS、390px、键盘顺序与日志/页面泄密扫描均已有本轮证据。**Task 8 门 1 的两个历史边界、门 5 的整分支审查、H03 验收驱动尚未闭合，因此只能宣布“主路径与门 2/3 闭合”，不能宣布“医院切片全部完成”，更不能宣布“医疗云系统全部完成”。**
