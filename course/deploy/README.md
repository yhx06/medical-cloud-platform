# 课程环境部署与验收

本目录是课程验收主链：JDK 8 → Tomcat 9（SaaS Portal）→ WildFly 10（PaaS/EJB）→ JNDI/JPA → MySQL。原 `backend/` 与 `frontend/` 是独立的 Java 21/Vue 实现，不要把它们的 JAR 或静态资源当作课程 WAR 部署。所有数据、账号和支付均为课堂虚构演示，不能用于真实诊疗。

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

启动 WildFly 后，在同一环境中注册 JDBC 模块、驱动与数据源；`ConnectorJar` 指向已下载的 MySQL Connector/J JAR。`configure-wildfly.ps1` 针对首次配置，若已有同名数据源，应先核查配置，不要盲目重复添加。

```powershell
& course/deploy/scripts/configure-wildfly.ps1 -WildFlyHome '<WildFly 10 路径>' -ConnectorJar '<mysql-connector-j.jar 路径>' -DbPort 3307
& course/deploy/scripts/deploy-wars.ps1 -TomcatHome '<Tomcat 9 路径>' -WildFlyHome '<WildFly 10 路径>'
```

启动 Tomcat，并等待两个 WAR 完成部署：WildFly `standalone/deployments/paas.war.deployed`、Tomcat 日志中 `/portal` 部署完成。更新已有 WAR 时，部署脚本须显式加 `-Replace`，它先备份旧 WAR。Tomcat 热部署期间可能短暂返回 404；待日志确认后再测。

```powershell
& course/deploy/scripts/smoke-test.ps1
& course/deploy/scripts/e2e-demo.ps1
```

`e2e-demo.ps1` 会真实写入预约、病历和支付订单，默认号源与患者只能成功预约一次；不要通过清空数据库来反复跑。若预约已创建但流程中断，可在尚未开始接诊时使用 `-AppointmentId '<已创建预约 UUID>'` 续跑。成功时输出订单为 `PAID`，以及 401/403/409 防护结果。入口是 `http://127.0.0.1:8088/portal/login`。

## 数据源与接口

WildFly JNDI 名称为 `java:/jdbc/MedicalCloudCourseDS`，JPA 持久化单元为 `medicalCloudCourse`。`wildfly-datasource.cli` 使用启动进程的环境变量引用密码，不在仓库保存凭据。可用 WildFly CLI 的 `/subsystem=datasources/data-source=MedicalCloudCourseDS:test-connection-in-pool` 检查连接。业务接口与角色见 [课程架构说明](../../docs/course-architecture/README.md)。

## 已知边界

支付只是数据库状态变更，没有第三方支付、清结算或退款；病历仅用于课程演示。课程版没有医院号源发布界面、监管统计、附件存储和审计中心，演示号源由种子 SQL 提供。当前本机验收使用 Tomcat 9.0.100 / WildFly 10.1.0.Final / MySQL 8.0.45 的隔离副本；教师原目录中的 Tomcat 9.0.24 与 WildFly 10.0.0.Final 未被覆盖，也尚未在那两个精确小版本上验证。MySQL 5.6 是课件示例版本，未做实机验证。
