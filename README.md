# 基于多租户医疗云的在线预约与诊疗服务平台

本仓库有两条互不覆盖的实现路径。课程验收主路径是 `course/`：JDK 8、Tomcat 9 门户 WAR、WildFly 10 PaaS WAR、MySQL/JNDI/JPA 和 PowerDesigner 模型，可演示登录、预约、接诊、病历及模拟缴费。`backend/` 与 `frontend/` 保留原 Java 21/Spring Boot/Vue 实现，便于对比和后续扩展。

按老师环境复现时请先看 [课程版部署与验收](course/deploy/README.md)、[架构及接口](docs/course-architecture/README.md)、[实验记录](docs/course-labs/README.md)。课程版入口为 `http://127.0.0.1:8088/portal/login`，隔离环境启动后可访问。以下快速开始仅适用于原 Java 21/Vue 版本。

## 原版技术栈（非课程验收主路径）

- 后端：Java 21、Spring Boot 3.4.5、Spring Security、JPA、Flyway
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router
- 基础设施：MySQL 8.4、MinIO、Docker Compose

## 快速开始

1. 将 `deploy/env.example` 复制为 `deploy/.env`，修改其中密码和 JWT 密钥。
2. 启动基础设施：`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`
3. 在当前终端载入同一组环境变量后启动后端：`mvn -f backend/pom.xml -Dspring-boot.run.profiles=dev spring-boot:run`
4. 安装并启动前端：`npm --prefix frontend install`，然后 `npm --prefix frontend run dev`
5. 访问 `http://localhost:5173`。

所有演示账号密码均为 `Demo@123456`：`patient_demo`、`doctor_a`、`hospital_a`、`platform_admin`。另有 `doctor_b` 用于演示第二租户隔离。

详细说明见 [开发指南](docs/development.md) 和 [冒烟测试](docs/smoke-test.md)。
