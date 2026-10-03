# 课程环境部署与验收

本目录是课程验收主链：JDK 8 → Tomcat 9（SaaS Portal）→ WildFly 10（PaaS/EJB）→ JNDI/JPA → MySQL。原 `backend/` 与 `frontend/` 是独立的 Java 21/Vue 实现，不要把它们的 JAR 或静态资源当作课程 WAR 部署。所有数据、账号和支付均为课堂虚构演示，不能用于真实诊疗。

P0 完成情况与实测证据见 [2026-10-02 验收记录](../../docs/course-labs/2026-10-02-p0-acceptance.md)。本次验证还覆盖角色越权返回 403、已开始号源拒绝预约、医生调科后旧号源拒绝预约，以及 MySQL 旧快照下保护已预约号源。

挂号目录下拉、完成预约快捷入口及筛选/导航增强见 [2026-10-03 交互验收](../../docs/course-labs/2026-10-03-interaction-acceptance.md)。本次新增只读目录接口，无数据库结构变更；更新两个 WAR 即可，不需要重复初始化或迁移数据库。

医院启停、全写入口冻结、本院/跨院脱敏统计与真实 MySQL/JTA 证据见 [2026-10-03 平台统计验收](../../docs/course-labs/2026-10-03-platform-statistics-acceptance.md)。本轮仍无数据库结构变更，保留旧 NULL 时间，JPA 使用 `validate`；更新 WAR，不运行初始化或旧库迁移脚本。

## 准备

- JDK 8、Maven、Tomcat 9、WildFly 10、MySQL 与 MySQL Connector/J 8.x；PowerDesigner 16.5 用于编辑 `docs/course-architecture/medical-cloud-course.pdm`。
- 建议复制 Tomcat/WildFly 到隔离目录，使用空的课程数据库；不要覆盖教师原安装、MySQL 默认实例或已有项目数据。示例端口：Tomcat 8088、WildFly 8080、隔离 MySQL 3307。教师现场端口以实际配置为准。
- 在启动 WildFly 的**同一终端**设置 `MEDICAL_COURSE_DB_USER`、`MEDICAL_COURSE_DB_PASSWORD`、长度至少 32 字符的 `MEDICAL_COURSE_JWT_SECRET`。密钥和数据库密码不要提交到 Git。Tomcat 可用 `MEDICAL_COURSE_PAAS_URL` 指向 `http://127.0.0.1:8080/paas/api`，未设置时使用此默认值。
- 仅在本机隔离环境加载 `seed-demo.sql`。演示账号 `patient_demo`、`doctor_a`、`doctor_b`、`hospital_a`、`platform_admin`、`disabled_user` 的密码均为 `Demo@123456`；停用账号应被拒绝。

## 构建与部署（PowerShell）

以下命令在仓库根目录执行；替换尖括号路径与端口。`schema.sql` 含建库建表语句，因此数据库初始化阶段应使用有权创建课程库的管理员账号，完成后再切换为仅访问 `medical_cloud_course` 的应用账号。脚本只操作明确指定的课程库，运行前仍应确认实例与端口。

```powershell
$env:JAVA_HOME = '<JDK 8 路径>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -f course/pom.xml clean verify

$env:MEDICAL_COURSE_DB_USER = '<初始化账号>'
$env:MEDICAL_COURSE_DB_PASSWORD = '<初始化密码>'
& course/deploy/scripts/setup-database.ps1 -MysqlCli '<mysql.exe 路径>' -Port 3307 -WithDemoData

$env:MEDICAL_COURSE_DB_USER = '<应用账号>'
$env:MEDICAL_COURSE_DB_PASSWORD = '<应用密码>'
$env:MEDICAL_COURSE_JWT_SECRET = '<至少 32 字符的随机密钥>'
& course/deploy/scripts/check-environment.ps1 -JavaHome $env:JAVA_HOME -TomcatHome '<Tomcat 9 路径>' -WildFlyHome '<WildFly 10 路径>' -MysqlCli '<mysql.exe 路径>'
```

启动 WildFly 后，在同一环境中注册 JDBC 模块、驱动与数据源；`ConnectorJar` 指向已下载的 MySQL Connector/J JAR。`Controller` 必须指向该隔离 WildFly 的管理地址（示例为 `127.0.0.1:9990`）。脚本先只读核对运行实例的 `home-dir` 与 `WildFlyHome`，不一致或无法核对时会在复制模块和写入 CLI 配置前停止。`configure-wildfly.ps1` 针对首次配置，若已有同名数据源，应先核查配置，不要盲目重复添加。

```powershell
& course/deploy/scripts/configure-wildfly.ps1 -WildFlyHome '<WildFly 10 路径>' -Controller '127.0.0.1:9990' -ConnectorJar '<mysql-connector-j.jar 路径>' -DbPort 3307
& course/deploy/scripts/deploy-wars.ps1 -TomcatHome '<Tomcat 9 路径>' -WildFlyHome '<WildFly 10 路径>'
```

启动 Tomcat，并等待两个 WAR 完成部署：WildFly `standalone/deployments/paas.war.deployed`、Tomcat 日志中 `/portal` 部署完成。更新已有 WAR 时，部署脚本须显式加 `-Replace`，它先备份旧 WAR。Tomcat 热部署期间可能短暂返回 404；待日志确认后再测。

```powershell
& course/deploy/scripts/smoke-test.ps1
& course/deploy/scripts/e2e-demo.ps1 -FixturePath "$env:TEMP\medical-p0-demo.json"
```

`e2e-demo.ps1` 为每个新进度文件生成独立 UUID 用户名、随机密码和未来号源，完整执行注册→医院发布→组合筛选→预约→取消→重约→开始→完成→缴费→医院订单查询。仅操作该虚构患者与号源，不清库、不修改已有种子号源。每次新场景新增 1 用户、1 号源、2 预约、1 病历、1 订单。成功输出 `PASS`、`PAID` 和 401/403/409 防护结果。入口是 `http://127.0.0.1:8088/portal/login`。

成功输出另包含 `PaidAt`、`CompletedAt` 和 `DoctorRecord`；脚本核对重复支付保留首次时间、医生历史病历隔离、已满号源隐藏，以及医院与患者不能相互调用对方的订单操作。无效日历日期、时段或检查点类型在发送 HTTP 请求前被拒绝。

中断后使用完全相同的 `-ApiBase` 和 `-FixturePath` 续跑；脚本从服务端查询已有状态，已完成场景再次运行返回相同预约/订单 ID，不新增业务行。进度文件通过原子替换保存，包含本地演示凭据，不应提交或截图；不要手改、共享或并发使用同一个文件。省略路径会在临时目录创建新文件并显示路径。新场景使用新文件；号源时间发生碰撞时脚本停止，换新文件重试。医院和医生登录使用种子账号，测试账户密码可通过 `-DemoPassword` 指定。

### 已有课程库升级

已有旧版课程库不能靠重复执行 `schema.sql` 升级（`IF NOT EXISTS` 不会增加列）。先停应用写入，用明确实例端口读取 `SELECT @@server_uuid` 并核对目标，再运行保护性迁移脚本。它核对完整旧结构、库名、端口和 UUID，先备份成功才执行 DDL；已升级结构或不匹配目标会停止。示例仅用于已确认的隔离实例：

```powershell
& course/deploy/scripts/upgrade-p0-database.ps1 -MySqlCli '<mysql.exe>' -MySqlDumpCli '<mysqldump.exe>' -HostName 127.0.0.1 -Port 3318 -Database medical_cloud_course -ExpectedServerUuid '<已核对 UUID>' -BackupPath '<不存在的备份文件完整路径>'
```

升级前后逐表比较行数，并保存备份。MySQL DDL 可能部分提交；失败后检查实际结构，不应盲目重跑，先将备份恢复到另一个空的隔离实例验证。迁移保留旧业务行，新增时间/主诉/备注列为 NULL，并将患者/号源唯一约束改为普通索引以允许取消后重约。数据库与 JVM 使用课程时区 `Asia/Shanghai`（启动 JVM 可加 `-Duser.timezone=Asia/Shanghai`），不要用机器本地时区推断取消截止或支付日期。

### 自动验收与报告截图

门户脚本的独立回归使用 Node 内置测试工具（仅开发验证需要 Node，Tomcat/WildFly 运行不需要）：

```powershell
node --test course/portal/src/test/js/portal.test.cjs
```

```powershell
$env:COURSE_P0_API_BASE = 'http://127.0.0.1:8180/paas/api'
$env:COURSE_P0_PORTAL_URL = 'http://127.0.0.1:8188/portal/login'
Invoke-Pester course/deploy/tests
```

模型/迁移保护检查无需容器；上述环境变量开启真实接口测试，执行新场景及再次续跑。冒烟脚本检查门户登录、注册页和号源 JSON 契约。截图使用虚构数据：① 注册/登录页；② 医院科室、医生及发布号源；③ 患者组合筛选、取消后重约；④ 医生开始、完成和历史病历；⑤ 患者已支付订单及医院订单筛选；⑥ 完整 ER 图；⑦ 构建、双容器日志与脚本 PASS 输出。隐藏密码、令牌及进度文件内容。

部署测试会启动临时 MySQL 实例，另建随机命名的 `course_slot_test_*` 数据库运行 `MySqlSlotSnapshotTest`，随后关闭并清理临时实例。它按先读取管理员快照、再提交患者预约、最后尝试撤回号源的顺序验证事务保护。无需手工设置 `COURSE_MYSQL_TEST_*`；测试脚本自动配置这些变量并恢复原值。普通 Maven 构建跳过这一条需要实机的测试，Pester 在临时实例上实际执行它。

医院生命周期与统计另有独立 disposable MySQL 测试。将 JDK 8、Maven、`mysql.exe`、`mysqld.exe` 加入当前终端路径，Pester 3.4 使用：

```powershell
$result = Invoke-Pester -Script course/deploy/tests/platform-statistics.Tests.ps1 -PassThru
if ($result.FailedCount -ne 0) { throw 'MySQL acceptance failed' }
```

脚本只创建唯一临时 datadir、loopback 闲置端口和 `course_platform_test_<32位hex>` 库；测试在 create-drop 前严格核对 URL/库名。它真实执行 24 项医院锁/旧快照写测试和 4 项统计测试，要求零失败、零错误、零跳过，最后正常关闭该实例、确认精确 datadir 的进程已退出才清理，并恢复环境变量。普通 `clean verify` 的 29 项跳过为这 28 项加原号源 MySQL 测试，不能将跳过当作实机证据。运行库不得用作该测试库。

已部署接口验收需教师准备自己的虚构专用医院和账号，再提供显式本地磁盘绝对路径的 JSON 文件（拒绝 UNC/相对路径）：

```powershell
& course/deploy/scripts/verify-platform-statistics.ps1 -PaasBaseUrl 'http://127.0.0.1:8180/paas/api' -FixturePath '<本地盘符:\专用夹具.json>'
```

夹具字段：`tenantId`、`tenantName`；`accounts.platform/hospital/doctor/patient` 各含 `username/password`；`resources` 含 `slotId/departmentId/doctorUserId/bookedAppointmentId/inProgressAppointmentId/completedAppointmentId/unpaidOrderId/paidOrderId`。所有 ID 为 UUID，医院名须为 `course_stats_acceptance_<32位小写hex>`，不能使用两家种子医院。准备未来未满号源、待诊/接诊中/已完成预约、未付/已付订单，且上述账号拥有这些资源。脚本先从平台分页列表核对医院 ID/名称，再校验资源归属；冻结 12 个写入口，检查历史读/新登录及聚合，然后重新启用并验证科室更新与付款，finally 恢复医院原状态。成功付款会保留，脚本不撤销业务记录；不得用于真实数据。夹具含密码，勿提交、截图或共享，脚本不打印凭据。

## 数据源与接口

WildFly JNDI 名称为 `java:/jdbc/MedicalCloudCourseDS`，JPA 持久化单元为 `medicalCloudCourse`。`wildfly-datasource.cli` 使用启动进程的环境变量引用密码，不在仓库保存凭据。可用 WildFly CLI 的 `/subsystem=datasources/data-source=MedicalCloudCourseDS:test-connection-in-pool` 检查连接。业务接口与角色见 [课程架构说明](../../docs/course-architecture/README.md)。

首次数据源配置显式设置 `TRANSACTION_REPEATABLE_READ`。已有隔离实例应先用 CLI 只读核对 `home-dir`、连接 URL 和管理端口，备份 `standalone.xml`，再仅修改该数据源的 `transaction-isolation` 并 reload；不要重复 add 或初始化数据库。读取属性确认 `TRANSACTION_REPEATABLE_READ`、连接测试成功，并在实际 JDBC/JTA 会话确认隔离级别 4（MySQL `REPEATABLE-READ`）。两个 JVM 均设置 `-Duser.timezone=Asia/Shanghai`，JDBC URL 包含 `serverTimezone=Asia/Shanghai`；仅有配置文件或 H2 测试不能证明实际 JTA 快照。

## 已知边界

支付只是数据库状态变更，没有第三方支付、清结算或退款；病历仅用于课程演示。医院管理/订单、平台医院启停和本院/跨院脱敏统计已实现；新建医院/医院管理员入口、附件存储和持久化审计中心未实现，也不代表医疗合规认证。2026-10-02/03 复测沿用教师目录的隔离副本：Oracle JDK 1.8.0_341、Tomcat 9.0.24、WildFly 10.0.0.Final（RESTEasy 3.0.14.Final），MySQL 8.0.45 与 Connector/J 8.0.33；端口为 8188 / 8180 / 10090 / 3318。原安装目录和默认服务未改动。MySQL 5.6 与其他教师机器的补丁版本、配置尚未实机验证。演示脚本目前通过有上限的列表恢复资源，号源或历史数据超过列表范围后，应使用新场景；按 ID 恢复与分页尚待补充。
