# 医疗云课程 Demo

多租户在线预约与诊疗平台，用于《云服务工程》课程演示和期末提交。**主版本在 `course/`，不是根目录的 Spring/Vue 旧版。** 仅限本机虚构数据、模拟诊疗与支付。

当前先交付基本 Demo；完整并发、故障恢复、附件及双浏览器验收未全部闭合，不能视为生产可用或课程全部要求已验收。

## 技术架构

```text
浏览器 → Tomcat 9：Servlet/JSP 门户（portal.war）
                  ↓ HTTP / JSON
          WildFly 10：JAX-RS + EJB/JTA + JPA（paas.war）
                  ↓                    ↓
          MySQL / JNDI 数据源    MinIO 私有附件存储（可选配置）
```

运行与构建使用 **Java 8**。`course/contract` 是共享契约，`course/attachment-sdk` 是隔离SDK依赖模块，不是第三个服务。当前数据库为 **007、13张表**，JPA使用 `validate`，不自动改表。MySQL8.0.45曾用于本机验收；教师MySQL5.6兼容目标尚需实测。

## 基本演示范围

| 角色 | 演示流程 |
| --- | --- |
| 患者 | 注册登录、筛选号源、预约/取消、查看病历订单、模拟缴费 |
| 医生 | 本人预约、开始接诊、填写并完成病历、历史记录 |
| 医院管理员 | 本院科室/医生/号源管理、订单查询 |
| 平台管理员 | 医院列表、医院与首管理员一并开通、管理员维护、医院启停 |

代码另含病情补充、私有草稿、病历更正、附件、审计、统计、资料维护与分页。其未闭合验收见[后续任务](docs/DEVELOPMENT-BACKLOG-2026-10-19.md)，不因有页面就声称所有边界已通过。

门户采用统一云控制台界面：深色导航、浅色工作区、本地图标与响应式表格，覆盖四角色及登录/注册页，无外部字体或前端框架依赖。界面更新需重新构建并部署 `portal.war`；历史 Demo 压缩包不会自动更新。

## 启动入口

- [基本Demo：环境、启动、账号与演示顺序](docs/DEMO.md)
- [详细部署与旧库升级](course/deploy/README.md)
- [架构与接口](docs/course-architecture/README.md)、[实验记录](docs/course-labs/README.md)
- [下一位AI接续入口](docs/AI-HANDOFF-2026-10-20.md)、[最新审查及修复状态](docs/reviews/2026-10-20-repair-checkpoint.md)

以下命令在含 `course/pom.xml` 的目录运行。本机权威工作树是 `E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`。

```powershell
$env:JAVA_HOME = '<JDK8目录>'
mvn -f course/pom.xml "-Dcourse.test.wildfly.home=<WildFly10目录>" clean verify
```

产物：`course/paas/target/paas.war`、`course/portal/target/portal.war`。测试依赖教师WildFly的真实提供器模块；缺少夹具时跳过的可选集成测试，不算真实数据库、事务或浏览器测试通过。

手工向**全新专属本机库**加载 `seed-demo.sql` 后，公开教学账号均用 `Demo@123456`：

| 角色 | 用户名 |
| --- | --- |
| 患者 | `patient_demo` |
| 医生 / 第二医院医生 | `doctor_a` / `doctor_b` |
| 医院管理员 | `hospital_a` |
| 平台管理员 | `platform_admin` |

自动创建的独立环境使用私有随机密码，**不适用公开种子密码**；在本机受限的 `accounts.json` 查看，不上传或分享。示例门户为 `http://127.0.0.1:8088/portal/login`，以实际Tomcat端口为准；历史地址不代表服务当前在线。

## 提交边界

- 提交源码、版本化SQL、测试、README和报告；不提交 `.local/`、运行数据库、备份、凭据、令牌、原始病历或 `target/`。
- 旧库不能重跑 `schema.sql` 当升级；先备份、核对实例和结构，使用对应保护升级器，不操作其他验收仍在用的库。
- `verify-hospital-onboarding.ps1` 是专项验收驱动，不是Demo启动器；缺必需观察时非零退出，不能删门制造成功。
- 不新增PowerDesigner图或实际70台服务器；旧图件仅为历史建模资料。
- `backend/`、`frontend/` 保留Java21/Spring Boot/Vue旧版；其[开发指南](docs/development.md)与课程WAR、数据库、命令不可混用。
