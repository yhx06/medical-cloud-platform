# 基于多租户医疗云的在线预约与诊疗服务平台

云服务工程课程项目基础框架。当前版本可演示 JWT 登录、四角色权限控制、租户隔离、角色工作台和统一错误响应；预约、接诊、订单、文件和统计保留清晰模块契约，便于小组并行迭代。

## 技术栈

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
