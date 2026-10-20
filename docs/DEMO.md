# 基本 Demo 提交与演示指南

本指南覆盖课堂基本流程，不代替全部专项验收。使用`course/`版本：Java8、Tomcat9门户、WildFly10业务服务、MySQL。仅本机虚构数据，不接真实医疗或支付。

## 1. 提交内容

提交课程分支源码和README，主要目录为`course/`、`docs/`。可按老师要求另行提交构建后的`portal.war`与`paas.war`，但不打包数据库目录、随机账号文件、日志、备份、密钥或`.local`。报告、录像、建模材料是独立交付项，不能仅凭Demo源码视为全部完成。

## 2. 环境与构建

准备JDK8、Maven3、Tomcat9、WildFly10、MySQL及Connector/J。MinIO仅用于附件；不演示附件时不用开启附件功能。教师安装只读，使用项目目录内的隔离容器副本、专属数据库实例和私有TEMP/TMP/java.io.tmpdir，不覆盖教师安装或旧运行环境。

在含`course/pom.xml`的目录执行：

```powershell
$env:JAVA_HOME = '<JDK8目录>'
mvn -f course/pom.xml "-Dcourse.test.wildfly.home=<WildFly10目录>" clean verify
if ($LASTEXITCODE -ne 0) { throw '构建未通过，不部署' }
```

使用本机完整Maven缓存时可加`-o`；首次缺依赖时不能为了离线成功删掉测试。产物为`course/paas/target/paas.war`和`course/portal/target/portal.war`。缺夹具的可选集成测试跳过，应如实记录。

## 3. 新建专属数据库

**仅对已经核对的全新、空的本机隔离实例执行。不要用于现有业务库或旧验收库。** 当前`schema.sql`为完整007结构、13表。旧库升级另读[详细部署](../course/deploy/README.md)，`IF NOT EXISTS`不能升级旧表。

先在初始化终端交互设置`MEDICAL_COURSE_DB_USER`与`MEDICAL_COURSE_DB_PASSWORD`，不把实际密码写入共享脚本、仓库或聊天。然后：

```powershell
& course/deploy/scripts/setup-database.ps1 `
  -MysqlCli '<mysql.exe>' -HostName 127.0.0.1 -Port <独立MySQL端口> -WithDemoData
```

这会创建`medical_cloud_course`并载入公开虚构种子。应用应切换到仅有该库所需权限的专属账号，不继续使用初始化管理员。公开种子密码仅限本机教学。

## 4. 配置并启动双容器

参数和详细流程见[部署说明](../course/deploy/README.md)。尖括号均须替换，不直接粘贴执行。

1. 在启动隔离WildFly的终端设置应用`MEDICAL_COURSE_DB_USER`、`MEDICAL_COURSE_DB_PASSWORD`和至少32字符随机`MEDICAL_COURSE_JWT_SECRET`；Java进程采用`Asia/Shanghai`时区。
2. 启动隔离WildFly，确认管理地址及home，再配置JDBC：

   ```powershell
   & course/deploy/scripts/configure-wildfly.ps1 `
     -WildFlyHome '<隔离WildFly目录>' -Controller '127.0.0.1:<管理端口>' `
     -ConnectorJar '<Connector/J jar>' -DbPort <独立MySQL端口>
   ```

3. 部署双WAR：

   ```powershell
   & course/deploy/scripts/deploy-wars.ps1 `
     -TomcatHome '<隔离Tomcat目录>' -WildFlyHome '<隔离WildFly目录>'
   ```

   存在WAR时默认拒绝覆盖；确认目标后才加`-Replace`，脚本备份旧WAR。
4. 在启动隔离Tomcat的终端设置`MEDICAL_COURSE_PAAS_URL=http://127.0.0.1:<业务端口>/paas/api`，再启动Tomcat。示例门户端口8088不是自动保证，按实际配置访问`/portal/login`。
5. 等WildFly出现`paas.war.deployed`、Tomcat部署完成，再运行只读冒烟：

   ```powershell
   & course/deploy/scripts/smoke-test.ps1 `
     -PortalUrl 'http://127.0.0.1:<门户端口>/portal/login' `
     -CatalogUrl 'http://127.0.0.1:<业务端口>/paas/api/catalog/slots?tenantId=11111111-1111-1111-1111-111111111111'
   ```

若127.0.0.1拒绝连接，先检查进程、端口和部署日志；不要打开本地HTML代替Tomcat/WildFly。仓库不是已托管的公网医疗网站。

### 自动生成的本机私有环境

使用其私有`fixture.json`的端口和`accounts.json`中的随机密码，不用公开种子密码。`new-hospital-onboarding-fixture.ps1`不是通用一键安装器：需要固定源码提交和本机工具路径；创建/正常停启必须核对owner、PID、创建时间、监听和数据库UUID。生命周期修复与本轮真实验证未闭合前，不直接操作旧环境。

## 5. 5–10分钟演示顺序

这是操作脚本，不声称本轮已经逐项实机执行。使用纯虚构病情，先按页面实际操作预演。

1. `hospital_a`：查看科室/医生，给`doctor_a`发布未来号源，设置人数与诊费。种子2030年号源也可用于预约。
2. `patient_demo`或新患者：筛选并预约；可取消后重约，展示状态变化。
3. `doctor_a`：开始接诊，填写虚构诊断/处理意见并完成，生成病历和待缴费订单。
4. 患者：查看病历、模拟缴费、刷新订单。
5. `hospital_a`：查看本院订单；`doctor_b`属于第二医院，用于说明租户边界，不展示其他患者数据。
6. `platform_admin`：查看医院与管理员；演示一次创建医院和首管理员。首次密码由平台设置，成功页不回显。

手工种子账号共用`Demo@123456`；自动环境另看私有账号文件。不要将真实密码或账号文件投影、截图、上传。附件故障与高级并发恢复不是本轮基本演示的必过项。

## 6. 已知限制与后续工作

本版不是完整上线系统。待处理/复验包括同患者并发跨院预约RR快照、医生资料冻结/停用竞态、部分分页与关联编辑选项、附件压力边界；H03真实JTA/迁移/恢复及完整浏览器矩阵也未闭合。按单用户基本流程演示，不宣称已解决上述边界。

后续按[接续入口](AI-HANDOFF-2026-10-20.md)、[全部开发任务](DEVELOPMENT-BACKLOG-2026-10-19.md)、[代码审查](reviews/2026-10-20-h03-pre-execution-review.md)推进，再交原AI review。
