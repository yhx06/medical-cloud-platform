# 医疗云课程环境适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 JDK 8、Tomcat 9、WildFly 10 和 MySQL 环境中完成医疗云的 SaaS/PaaS/IaaS 分层部署，并演示预约、接诊和缴费闭环。

**Architecture:** 新建独立 `course/` 工程，不修改既有 Java 21 业务实现。Tomcat 9 托管 Servlet/JSP SaaS 门户并通过明确的 HTTP/JSON 契约调用 WildFly 10；WildFly 10 托管 JAX-RS/EJB PaaS 组件，经 JNDI DataSource/JPA 访问独立课程数据库。

**Tech Stack:** Java 8、Maven、Servlet/JSP、JAX-RS、EJB、JPA 2.1、Tomcat 9、WildFly 10、MySQL、JUnit 4/5（兼容 Java 8）、PowerDesigner。

**Spec:** `docs/superpowers/specs/2026-09-26-medical-cloud-course-environment-design.md`

## Global Constraints

- 主链在 JDK 8 下编译，产物分别为 Tomcat 9 和 WildFly 10 可部署的 WAR。
- 医疗云的预约、诊断与缴费必须来自真实持久化调用，不能只用静态页面模拟。
- SaaS 门户不直连数据库；PaaS 层从认证上下文确定角色和租户。
- 独立的 `medical_cloud_course` 数据库不得改动现有 `medical_cloud` 数据。
- 实验报告和部署说明区分真实运行结果与尚未在本机验证的步骤。

## Review Focus

- 旧 JWT 继续访问停用账号或租户：业务接口应拒绝。
- 患者同时抢占最后一个号源：仅一笔预约应提交。
- 医生跨租户或跨医生读取接诊：应返回 403。
- 完成的病历被重复覆盖或重复生成订单：应维持原记录和单个订单。
- Tomcat 无法连接 WildFly：门户应给出可理解的错误且不泄露凭据。

---

### Task 1: Java 8 工程与服务契约

**Files:**
- Create: `course/pom.xml`
- Create: `course/contract/pom.xml`
- Create: `course/contract/src/main/java/com/medcloud/course/contract/*.java`
- Create: `course/contract/src/test/java/com/medcloud/course/contract/ContractTest.java`
- Create: `course/paas/pom.xml`
- Create: `course/portal/pom.xml`

**Interfaces:**
- Produces: `ApiResponse<T>`、`ApiError`、`LoginRequest/Response`、`Slot/Appointment/Record/Order` DTO 与统一错误码。

- [ ] 测试先行：编写契约 JSON 序列化测试，固定 `code/message/data` 结构与角色字段。
- [ ] 在 JDK 8 下运行 `mvn -f course/pom.xml test`，确认测试因契约类缺失失败。
- [ ] 实现最小公共契约及 WAR 模块 Maven 配置，不引入 `jakarta.*`。
- [ ] 再运行 `mvn -f course/pom.xml test`，确认通过且两个 WAR 可打包。
- [ ] 提交 `build: scaffold Java 8 course modules and service contracts`。

### Task 2: WildFly 数据资源与基础目录

**Files:**
- Create: `course/deploy/schema.sql`
- Create: `course/deploy/wildfly-datasource.cli`
- Create: `course/paas/src/main/resources/META-INF/persistence.xml`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/tenant/*.java`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/schedule/*.java`
- Test: `course/paas/src/test/java/com/medcloud/course/paas/DirectoryPersistenceTest.java`

**Interfaces:**
- Consumes: Task 1 DTO。
- Produces: `DirectoryService` 的医院、科室、医生、未来号源读取与创建方法；JNDI 名称 `java:/jdbc/MedicalCloudCourseDS`。

- [ ] 测试先行：在独立测试库中创建两家医院和号源，查询必须带租户与日期条件。
- [ ] 运行 `mvn -f course/pom.xml -pl paas -am test`，确认失败来自缺少实体/服务。
- [ ] 实现兼容 MySQL 的建表脚本、JPA 实体、EJB 服务和 JNDI 配置。
- [ ] 重跑测试并提交 `feat: add WildFly JPA directory and schedule`。

### Task 3: PaaS 身份与预约接口

**Files:**
- Create: `course/paas/src/main/java/com/medcloud/course/paas/auth/*.java`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/appointment/*.java`
- Test: `course/paas/src/test/java/com/medcloud/course/paas/BookingIsolationTest.java`

**Interfaces:**
- Consumes: Task 2 JPA 实体和 Task 1 契约。
- Produces: `POST /api/auth/login`、`GET /api/catalog/slots`、`POST /api/patient/appointments`、`GET /api/doctor/appointments`；JWT 校验后的用户上下文。

- [ ] 测试先行：正确登录、停用账号拒绝、重复预约拒绝、容量与跨租户并发边界。
- [ ] 运行定向测试确认 RED。
- [ ] 实现 JAX-RS 资源、令牌校验、EJB 事务及数据库约束。
- [ ] 定向与模块测试 GREEN，提交 `feat: expose course appointment services`。

### Task 4: 接诊、账单和模拟缴费

**Files:**
- Create: `course/paas/src/main/java/com/medcloud/course/paas/consultation/*.java`
- Create: `course/paas/src/main/java/com/medcloud/course/paas/order/*.java`
- Test: `course/paas/src/test/java/com/medcloud/course/paas/ClinicalPaymentFlowTest.java`

**Interfaces:**
- Consumes: Task 3 已预约对象和用户上下文。
- Produces: 医生开始/完成接诊、患者读取本人病历和订单、幂等模拟支付的 REST 端点。

- [ ] 测试先行：从预约到完成病历、单笔账单和支付；跨医生/患者拒绝；完成后不可覆盖。
- [ ] 定向测试 RED 后实现 EJB/JPA 原子事务与 REST 资源。
- [ ] 模块测试 GREEN，提交 `feat: complete course consultation and payment flow`。

### Task 5: Tomcat SaaS 门户与 CloudServiceFactory

**Files:**
- Create: `course/portal/src/main/java/com/medcloud/course/portal/factory/*.java`
- Create: `course/portal/src/main/java/com/medcloud/course/portal/web/*.java`
- Create: `course/portal/src/main/webapp/WEB-INF/views/*.jsp`
- Create: `course/portal/src/main/webapp/assets/*`
- Test: `course/portal/src/test/java/com/medcloud/course/portal/PortalFlowTest.java`

**Interfaces:**
- Consumes: Task 1–4 HTTP/JSON 契约。
- Produces: Tomcat 9 中可访问的登录、医院、患者、医生和平台门户；`CloudServiceFactory` 根据角色返回界面门面。

- [ ] 测试先行：工厂按角色选择门面，门户经 HTTP 请求 PaaS；PaaS 不可达时显示稳定错误。
- [ ] 定向测试 RED 后实现 Servlet/JSP 页面、会话处理及 HTTP 客户端。
- [ ] 模块测试 GREEN，提交 `feat: serve course SaaS portal from Tomcat`。

### Task 6: 环境、建模和端到端验收

**Files:**
- Create: `course/deploy/README.md`
- Create: `course/deploy/scripts/*.ps1`
- Create: `docs/course-architecture/*`
- Create: `docs/course-labs/*`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1–5 WAR 与数据库脚本。
- Produces: 安装、配置、部署、验证和课程报告流程；PowerDesigner 源模型与导出图（工具可用时）。

- [ ] 在 JDK 8 下执行 `mvn -f course/pom.xml clean verify`，记录测试总数。
- [ ] 将两个 WAR 真实部署到 Tomcat 9/ WildFly 10，检查 JNDI 数据源、入口和完整诊疗流程。
- [ ] 验证停用账号、跨租户和重复预约，并截取实验 1–5 所需证据。
- [ ] 补齐 PowerDesigner 模型、接口目录、实验报告和实际限制。
- [ ] 提交 `docs: deliver course environment and verification evidence`，推送课程分支。
