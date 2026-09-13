# 本地开发指南

## 环境要求

Java 21、Maven 3.9+、Node.js 24+、npm 11+、Docker Desktop 与 Docker Compose 2.x。

## 服务与端口

| 服务 | 端口 | 用途 |
|---|---:|---|
| MySQL | 3306 | 身份、租户及后续业务数据 |
| MinIO API | 9000 | 处方、报告等对象文件 |
| MinIO Console | 9001 | 本地对象存储管理 |
| Spring Boot | 8080 | `/api` 与健康检查 |
| Vite | 5173 | 前端开发服务器 |

若端口冲突，请先停止占用程序，或同步修改 Compose 端口、后端配置和 Vite 代理。

## 验证命令

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend test -- --run
npm --prefix frontend run build
docker compose -f deploy/docker-compose.yml --env-file deploy/env.example config
```

测试使用 H2 的 MySQL 兼容模式，不依赖本地容器。实际开发使用 MySQL；Flyway 在启动时自动创建身份与租户表及虚构演示账号。

## 安全边界

后端只从签名 JWT 获取用户、角色和租户，不接受客户端伪造租户身份。平台管理员只能调用显式开放的平台管理接口，不自动绕过业务对象的租户校验。示例密钥只用于本地开发，提交或部署前必须替换。
