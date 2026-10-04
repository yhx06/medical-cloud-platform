# 医院启停与平台统计验收（2026-10-03）

本记录对应课程版 JDK 8 → Tomcat 9 → WildFly 10/EJB/JTA → JNDI/JPA → MySQL，全部账号、诊疗与金额为虚构数据。只更新隔离副本 WAR/数据源隔离属性，不初始化运行库，不迁移表、不回填旧时间，不修改教师原安装或默认 MySQL 服务。

## 需求与证据

| 需求 | 实测结论 | 证据方式 |
| --- | --- | --- |
| FR-TEN-05 | 平台分页查看医院、启停与 expectedStatus 冲突保护；停用冻结 12 个写入口，原会话/新登录保留授权历史，启用恢复正常写 | 真实 API、认证门户 GET/POST；24 项真实 MySQL 两事务测试 |
| FR-STAT-01 | 医院只读本院汇总/趋势、零日及旧时间提示；拒绝 tenantId 输入；停用仍可查 | 真实医院接口、白名单 SQL 对账、桌面/390×844 页面 |
| FR-STAT-02 | 平台跨院总计/每日/分院相等，医院存量独立于日期，含停用/零业务医院；不返回个人/诊疗正文 | MySQL 聚合、响应白名单、权限与隐私回归 |
| AT-07 | 日期/NULL/金额、锁交错、旧快照与容器 JTA 一致性；CSRF/401/无脚本表单链路及冻结提示 | 4 项真实 MySQL 统计测试、真实 JTA QA 探针、认证 raw HTTP、浏览器检查 |

12 个冻结入口为预约、取消、开始、完成、未付支付、重复已付支付，以及科室/医生/号源各 create/update。冻结均返回 409，业务状态、时间和金额快照不变；角色越权 403、医院统计附加 tenantId 400、状态 PUT 额外字段 400。独立停用科室、医生账号及撤回号源不会因医院重新启用而恢复。两家原演示医院保持 ACTIVE，原业务仍为 12 条预约、6 条订单。

## 统计口径

`from/to` 为严格有效的 `yyyy-MM-dd`，首尾包含，缺省为上海当天及前 29 天；单端、倒置、超过 366 天等返回 400。预约按下单日期 `appointment.booked_on`，不是就诊 `slotDate`，四状态为所选下单群体的查询时状态。接诊开始/病历完成/付款按真实事件时间；订单生成数/应收金额按同租户同预约关联病历的完成时间。事件采用上海本地 DATETIME 的半开范围，不再偏移八小时。

空日填零；总计等于每日之和、全部分院之和。金额采用 DECIMAL/BigDecimal，页面为元、两位小数。NULL 时间保持 NULL，不能用付款状态或下单日补造事件；`legacyUnknownTime` 是全量未知时间补充，不加进所选区间。平台医院 total/active/disabled 是同一快照的存量，不受日期筛选影响。统计只投影聚合及医院 ID/名称/状态，无患者身份、明细 ID、诊断、主诉、建议、备注、密码或令牌。

## 自动化结果

| 检查 | 最终结果 |
| --- | --- |
| `mvn -f course/pom.xml clean verify` | BUILD SUCCESS；208 项，0 failure、0 error、29 skipped（23:11:40 +08） |
| disposable MySQL Pester 3.4 | 1 passed、0 failed、0 skipped；内部 Java 24 生命周期 + 4 统计，全部零失败/错误/跳过 |
| `node --test course/portal/src/test/js/portal.test.cjs` | 6 passed，0 failed/skipped |
| RESTEasy 路由 + API/context focused 回归 | 11 项，0 failure/error/skipped |
| `configure-wildfly.Tests.ps1` | controller preflight PASS，显式隔离/端口/环境引用检查通过 |
| 部署 API 脚本 | PASS，FrozenWrites=12，历史读/新登录/重新启用通过 |
| 2026-10-04 真实 Tomcat 统计服务 500 补验 | 医院/平台分区错误、独立资源保留、无假零指标/趋势、恢复和卸载 PASS |

普通 Maven 的 29 项跳过为新增 28 项和已有 `MySqlSlotSnapshotTest`，因为没有 disposable 环境变量；不能作为实际 MySQL 通过证据。新增 28 项已通过独立 Pester 实际运行。运行方式见 [部署说明](../../course/deploy/README.md)，Pester 3.4 必须用 `-Script -PassThru`。

医院锁测试每个业务线程有独立 EntityManager，用 latch 确認业务医院读锁已获得、竞争启停已到写锁前，再检查停用等待；释放业务后停用提交。另一交错先建立 managed ACTIVE/旧快照，独立事务提交停用，再调用真实写服务，锁定 refresh 看到 DISABLED 并拒绝，完整业务表快照不变。Future 上限 10 秒、数据库锁等待 5 秒，无共享 EntityManager/无限等待。统计测试实际执行 MySQL DATE/DATETIME/NULL、Shanghai 午夜边界（JVM 临时设洛杉矶），以及首聚合后竞争支付/停用提交：本次 revenue 0.00，下次 39.10，各报告内部一致。

部署曾暴露 RESTEasy `/hospital` 类资源遮蔽根资源上的 `/hospital/context`、`/hospital/statistics`。先保留真实 registry RED（5 项中 2 failure），再将这两个方法归入 `HospitalResource`，医生/平台方法归入明确类级路径，11 项 focused GREEN，并重跑上述最终完整构建与 Node。

## 专用夹具与 SQL 对账

仅新增一个 `course_stats_acceptance_<32位hex>` 虚构医院及其 2 科室、5 账号、2 医生档案、3 号源、6 预约、3 病历、3 订单。夹具文件为本地 ignored 文件，含凭据，不提交。本院区间为 2026-10-03；验收中重新启用后将一笔 39.10 未付订单支付，付款保留。

| 指标 | API 与白名单 SQL 实际值 |
| --- | --- |
| 预约总数 / 待诊 / 接诊中 / 取消 / 完成 | 5 / 2 / 1 / 0 / 2 |
| 开始事件 / 完成病历事件 | 3 / 2 |
| 生成订单数 / 应收金额 | 2 / 78.20 |
| 实付订单数 / 模拟实收 | 2 / 78.20 |
| 全量未知开始 / 完成时间 | 1 / 1 |
| 全量未知生成时间订单数 / 金额 | 1 / 12.30 |
| 全量未知付款时间订单数 / 金额 | 1 / 12.30 |
| 平台医院总数 / 启用 / 停用（恢复后） | 3 / 3 / 0 |

SQL 使用专用医院范围和聚合投影，订单/病历关联同时匹配 tenant_id 与 appointment_id，没有导出患者或诊疗正文。实际 session `REPEATABLE-READ`；服务器 NOW 与 UTC 相差 8 小时。验收结束医院与临时停用的专用管理员账号均恢复 ACTIVE。

## 实际容器 JTA 与运行配置

隔离运行目录 `C:/Users/yhx/.codex/task8-runtime-20260928`：Oracle JDK 1.8.0_341、Tomcat 9.0.24（8188）、WildFly 10.0.0.Final（8180，管理10090）、MySQL 8.0.45（3318）。两个 JVM 为 Asia/Shanghai，JDBC URL 设置该时区；原数据源未显式声明隔离，备份 standalone.xml 后仅写 `TRANSACTION_REPEATABLE_READ`、reload，读回并连接测试成功。持久化仍为 JTA/validate。

经批准的临时 QA WAR 使用实际 JNDI/JPA/MySQL、REQUIRED 报告 EJB 与独立 REQUIRES_NEW 竞争 EJB 代理。nonce 缺失请求 403；注入 EntityManager/Query 观察 seam 在首个聚合后暂停，竞争者实际提交付款和停用，再继续同一生产 StatisticsService 聚合实现。实际 JTA key 非空，JDBC isolation=4；首报告 revenue 39.10、active=3，下一事务 revenue 78.20、active=2，全部指标每日/分院/总计自洽。finally 精确恢复原医院/订单/支付时间，并卸载 QA WAR；独立查询只剩 paas.war。此证据证明真实 JTA 下生产聚合实现，不是宣称生产 REST 请求曾被原地暂停。

## 门户与证据边界

桌面与 390×844 浏览器观察覆盖平台医院表格启停、冻结提示/只读历史、本院趋势、恢复写控件；小屏 body 不横向溢出，管理/统计/趋势表格内部横向滚动，趋势区域可键盘滚动。认证 raw HTTP GET/POST 实测状态表单、筛选、CSRF 403、老/新门户会话、直达病历账单；这是无 JavaScript 执行的表单链路证据，不是浏览器实际禁用 JS 的截图。

非法统计日期 400 时订单仍显示、无伪造零趋势；实际旧门户会话遇到停用账号 401 后只显示登录提示，未留下订单/趋势私有数据。生产部署 slotDate 实际为 ISO 日期字符串，上海今日 00:00 已开诊预约无取消按钮、未来预约有；整数日期兼容另有 JSP 回归。

2026-10-04 补验了“仅统计服务 500、其他服务正常”的真实 Tomcat 渲染：从最终 portal.war 建唯一 ignored QA 副本，仅配置现有 `paasBaseUrl` seam 到 nonce 保护的 loopback 代理，不修改产品类/JSP、不覆盖或重启 `/portal`。代理只对 `/hospital/statistics`、`/platform/statistics` 返回 500；登录及科室/医生/号源/订单/医院上下文/平台医院列表透传真实 WildFly，均实测 200。两个统计接口各观察到 2 次真实 500。医院页保留专用科室、医生、号源及两笔订单 ID；平台页保留专用医院 ID/名称和状态表单。两个统计区显示“诊疗服务暂时不可用，请稍后重试”，无 metrics-grid/metric-card/统计范围或每日数据行，趋势说明为暂不可用，未用假零替代。

切换代理为健康透传后，两角色同一会话恢复指标和趋势；finally 校验精确本次路径后移除 context descriptor，实际 QA 路径返回 404，代理正常 close/进程退出/监听关闭。再独立登录原 `/portal` 的医院/平台角色，资源、指标与趋势均正常。未注入独立超时故障；非法日期 400 仍仅是输入降级证据，不用于替代这次服务 500 验收。临时源/生成副本保留 ignored 供复核，日志 `task-6-statistics-500.log`。未验证 MySQL 5.6/其他教师机器；未实现新建医院/医院管理员、附件、持久化审计、真实支付退款或医疗合规。

日志/截图与 nonce 保护 QA 源码保留在本地 ignored `.superpowers/sdd/2026-10-03-medical-cloud-course-platform-statistics/`、`.superpowers/platform-statistics-qa/`，review 可定向核查，不随 Git 提交。有效截图包括 `platform-disabled-390.jpg`、`hospital-frozen-390.jpg`、`hospital-reenabled-desktop.jpg`（实际为稳定每日趋势）；`hospital-reenabled-stats-390.jpg` 实际是恢复管理控件。部分锚点切换初始截图仍处平滑滚动中，尤其 `platform-390.jpg`，不作为统计布局完成证据。浏览器已恢复桌面尺寸。

前两次 disposable harness 的 Java 测试均通过，但 Windows launcher/child 退出导致清理失败；两处已关闭的临时 datadir 残留保留，未重试被安全策略拒绝的删除。最终 harness 正常 SQL SHUTDOWN、确认精确进程退出后清理通过。残留精确路径与过程见本地 Task 6 报告；它们不是运行库，也不能据此删除其他 MySQL 数据。

## 2026-10-04 最终分页修正

最终评审发现 RESTEasy 在进入平台医院列表方法前转换 `int` 查询参数，非数字或整数溢出会绕过认证并返回 500。现接收原始字符串，先认证并检查平台角色，再解析和验证分页；默认 1/20、size 1–100 和 long 偏移上限保持，服务层数据库角色检查仍保留。坏参数不调用医院列表服务，公共错误体复用现有 400；匿名/无效令牌/停用账号仍为 401，非平台角色仍为 403。

真实 RESTEasy dispatch 回归先 RED：34 项、26 assertion failure、0 error/skipped，其中匿名坏参数实际 500 而预期 401；修正后 34 项全部通过。连同现有角色、偏移、路由和错误映射测试，focused 共 57 项零失败/错误/跳过。最终 `mvn -f course/pom.xml clean verify` 于 2026-10-04 14:16:05 +08 完成：242 项（contract 2 / PaaS 175 / portal 65），0 failure/error、29 skipped；跳过原因及之前实际 MySQL 验收仍如上，未重复数据库业务验收。Node 回归 6/6。

仅备份并更新现有隔离 WildFly 的 PaaS WAR；原包保留为 `paas.war.backup-final-pagination-20261004141851`。实际 GET 覆盖 page/size 非数字、有符号整数溢出及偏移溢出：匿名 401、平台 400、无效 Bearer 401、医院/患者角色 403。正常缺省分页、医院列表/上下文/统计均为 200，专用医院事件与金额、平台医院存量保持预期；原 `/portal` 医院/平台页面均有真实指标/趋势且无分区错误。未初始化/迁移数据库、修改账号/业务状态或教师环境，未替换门户 WAR/数据源配置。部署标记沿用 WAR 时间戳导致首次 checker 误报 timeout，随后仅校验哈希与实际响应确认成功，没有二次部署；日志为 ignored `final-fix-runtime.log`、`final-fix-runtime-verified.log`，完整 RED/GREEN/构建证据见同目录 `final-fix-report.md`。小屏交互证据另见最终控制器 `task-6-ui-controller.log` 与 `controller-hospital-trend-390.jpg`；本次未重跑浏览器、禁用 JS 或注入超时。
