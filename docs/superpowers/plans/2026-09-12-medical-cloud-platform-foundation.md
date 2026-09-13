# Medical Cloud Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可启动、可登录并能自动验证多租户隔离和四角色 RBAC 的医疗云课程项目基础框架。

**Architecture:** 使用 Vue 3 前端和 Spring Boot 模块化单体后端；后端从 JWT 构造可信的当前用户与租户上下文，业务包之间通过应用服务协作。MySQL 和 MinIO 用于本地运行，H2 MySQL 兼容模式用于快速自动化测试。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring Security、JPA、Flyway、JWT、Maven、Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus、Vitest、MySQL 8、MinIO、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-09-12-medical-cloud-platform-design.md`

## Global Constraints

- 包根路径固定为 `com.medcloud.platform`，前后端接口统一使用 `/api` 前缀。
- 后端只信任认证信息中的账号、角色和租户上下文，不信任请求头或请求体直接提交的租户身份。
- 患者、医生、医院管理员、平台运营管理员分别使用 `PATIENT`、`DOCTOR`、`HOSPITAL_ADMIN`、`PLATFORM_ADMIN`。
- 基础框架只真正实现认证、当前用户、租户隔离演示和角色工作台；其余业务模块只定义状态、接口类型和清晰入口。
- 演示数据全部虚构；密码保存 BCrypt 哈希；JWT 密钥只从环境变量或测试配置读取。
- 每项业务代码遵循测试先行；配置文件与生成式脚手架通过启动或配置校验验收。

---

### Task 1: 初始化仓库和 Spring Boot 后端

**Files:**
- Create: `.gitignore`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/medcloud/platform/MedicalCloudApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-dev.yml`
- Test: `backend/src/test/java/com/medcloud/platform/MedicalCloudApplicationTest.java`

**Interfaces:**
- Produces: 可由 Maven 启动的 Spring Boot 应用，后续任务共享 `com.medcloud.platform` 包和配置体系。

- [ ] **Step 1: 初始化版本库与忽略规则**

Run:

```powershell
git init
```

Create `.gitignore` with exact entries for `target/`, `node_modules/`, `.idea/`, `.vscode/`, `.env`, `dist/`, logs and OS metadata; keep `env.example` tracked.

- [ ] **Step 2: 创建 Maven 构建文件和失败的上下文测试**

Use Spring Boot parent `3.4.5`, Java `21`, and dependencies: web, validation, security, data-jpa, actuator, Flyway, MySQL connector, H2 test, Spring Security Test, and JJWT `0.12.6`.

```java
@SpringBootTest
class MedicalCloudApplicationTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 3: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml test`

Expected: FAIL because `MedicalCloudApplication` and application configuration do not exist.

- [ ] **Step 4: 添加最小启动类与配置**

```java
@SpringBootApplication
public class MedicalCloudApplication {
    public static void main(String[] args) {
        SpringApplication.run(MedicalCloudApplication.class, args);
    }
}
```

`application.yml` activates no environment implicitly and configures actuator health exposure. `application-dev.yml` reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY` with local-development defaults only where they are non-sensitive.

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml test`

Expected: PASS with one context test.

- [ ] **Step 6: 提交**

```powershell
git add .gitignore backend
git commit -m "build: initialize Spring Boot backend"
```

### Task 2: 建立统一 API 与错误处理

**Files:**
- Create: `backend/src/main/java/com/medcloud/platform/common/api/ApiResponse.java`
- Create: `backend/src/main/java/com/medcloud/platform/common/api/ApiError.java`
- Create: `backend/src/main/java/com/medcloud/platform/common/error/ErrorCode.java`
- Create: `backend/src/main/java/com/medcloud/platform/common/error/BusinessException.java`
- Create: `backend/src/main/java/com/medcloud/platform/common/error/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/medcloud/platform/common/web/RequestIdFilter.java`
- Test: `backend/src/test/java/com/medcloud/platform/common/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiResponse<T>(T data, String requestId)` and `ApiError(String code, String message, String requestId, Map<String,String> fieldErrors)`。
- Produces: `BusinessException(ErrorCode code)` and stable error codes used by all later controllers.

- [ ] **Step 1: 写失败的参数校验响应测试**

Create a test-only controller with a `@NotBlank` field and assert HTTP 400:

```java
mockMvc.perform(post("/test/validation")
        .contentType(APPLICATION_JSON)
        .content("{\"name\":\"\"}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    .andExpect(jsonPath("$.requestId").isNotEmpty())
    .andExpect(jsonPath("$.fieldErrors.name").exists());
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=GlobalExceptionHandlerTest test`

Expected: FAIL because the shared response and exception handler are absent.

- [ ] **Step 3: 实现最小统一响应、错误码与请求标识**

Define exact codes `AUTH_INVALID_CREDENTIALS`, `AUTH_FORBIDDEN`, `VALIDATION_ERROR`, `TENANT_ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, and `INTERNAL_ERROR`. `RequestIdFilter` accepts no external trusted ID; it generates a UUID per request, stores it as a request attribute, and returns it in `X-Request-Id`.

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml -Dtest=GlobalExceptionHandlerTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main backend/src/test
git commit -m "feat: add API error contract"
```

### Task 3: 建立身份、租户数据和迁移

**Files:**
- Create: `backend/src/main/java/com/medcloud/platform/identity/domain/UserRole.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/domain/UserStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/domain/UserAccount.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/infrastructure/UserAccountRepository.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/domain/Tenant.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/domain/TenantStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/infrastructure/TenantRepository.java`
- Create: `backend/src/main/resources/db/migration/V1__identity_and_tenant.sql`
- Create: `backend/src/main/resources/db/migration/V2__demo_accounts.sql`
- Create: `backend/src/test/resources/application.yml`
- Test: `backend/src/test/java/com/medcloud/platform/identity/infrastructure/IdentityMigrationTest.java`
- Test: `backend/src/test/java/com/medcloud/platform/identity/infrastructure/DemoPasswordHashTest.java`

**Interfaces:**
- Produces: `Optional<UserAccount> findByUsername(String username)`。
- Produces: `Optional<Tenant> findByIdAndStatus(UUID id, TenantStatus status)`。

- [ ] **Step 1: 写失败的迁移与演示数据测试**

```java
@SpringBootTest
class IdentityMigrationTest {
    @Autowired UserAccountRepository users;
    @Test void loadsFourRolesAcrossTwoTenants() {
        assertThat(users.findByUsername("patient_demo")).isPresent();
        assertThat(users.findByUsername("doctor_a")).get()
            .extracting(UserAccount::getRole).isEqualTo(UserRole.DOCTOR);
        assertThat(users.findByUsername("hospital_a")).isPresent();
        assertThat(users.findByUsername("platform_admin")).isPresent();
    }
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=IdentityMigrationTest test`

Expected: FAIL because entities, repositories and migrations are missing.

- [ ] **Step 3: 生成并验证演示密码哈希**

Create a temporary focused test that uses the same Spring encoder as production:

```java
@Test
void generatesVerifiedDemoHash() {
    PasswordEncoder encoder = new BCryptPasswordEncoder(10);
    String hash = encoder.encode("Demo@123456");
    assertThat(encoder.matches("Demo@123456", hash)).isTrue();
    System.out.println("DEMO_BCRYPT=" + hash);
}
```

Run: `mvn -f backend/pom.xml -Dtest=DemoPasswordHashTest test`

Expected: PASS and print one `$2a$10$...` hash. Copy that complete value into `V2__demo_accounts.sql`; the migration must not contain `{noop}` or a clear-text password. Keep the test because it documents the demo credential contract, but remove the console output after copying the hash.

- [ ] **Step 4: 实现实体、约束和演示数据**

Create `tenant(id, name, status, created_at)` and `user_account(id, username, password_hash, role, status, tenant_id, created_at)`. Add unique username, tenant foreign key and status indexes. Seed tenant A and B plus four role examples, with doctor and hospital-admin examples bound to tenant A; use a documented BCrypt hash for the development-only password `Demo@123456`.

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml -Dtest=IdentityMigrationTest test`

Expected: PASS and Flyway reports two applied migrations.

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main backend/src/test
git commit -m "feat: add identity and tenant model"
```

### Task 4: 实现 JWT 登录和当前用户

**Files:**
- Create: `backend/src/main/java/com/medcloud/platform/security/CurrentUser.java`
- Create: `backend/src/main/java/com/medcloud/platform/security/JwtService.java`
- Create: `backend/src/main/java/com/medcloud/platform/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/medcloud/platform/security/SecurityConfiguration.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/application/AuthenticationService.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/api/AuthController.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/api/LoginRequest.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/api/LoginResponse.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/api/CurrentUserResponse.java`
- Test: `backend/src/test/java/com/medcloud/platform/identity/api/AuthControllerTest.java`

**Interfaces:**
- Produces: `LoginResponse login(String username, String password)`。
- Produces: `CurrentUser(UUID userId, String username, UserRole role, UUID tenantId)`。
- Produces: `POST /api/auth/login` and `GET /api/me`。

- [ ] **Step 1: 写失败的认证测试**

Test four behaviors separately: correct password returns token, wrong password returns `AUTH_INVALID_CREDENTIALS`, disabled user is rejected, and `/api/me` without a bearer token returns 401.

```java
mockMvc.perform(post("/api/auth/login")
        .contentType(APPLICATION_JSON)
        .content("{\"username\":\"doctor_a\",\"password\":\"Demo@123456\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
    .andExpect(jsonPath("$.data.user.role").value("DOCTOR"));
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=AuthControllerTest test`

Expected: FAIL because login, JWT and security rules are missing.

- [ ] **Step 3: 实现最小认证链路**

Use BCrypt verification and JJWT HS256 signing. JWT claims contain `sub`, `username`, `role`, and optional `tenantId`; expiration is 30 minutes. Permit only `/api/auth/login` and `/actuator/health`, require authentication for every other endpoint, disable server sessions, and return JSON errors for 401 and 403.

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml -Dtest=AuthControllerTest test`

Expected: PASS for valid login, invalid password, disabled account and unauthenticated access.

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main backend/src/test
git commit -m "feat: implement JWT authentication"
```

### Task 5: 实现 RBAC 与租户隔离证明接口

**Files:**
- Create: `backend/src/main/java/com/medcloud/platform/tenant/application/TenantAccessPolicy.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/api/PlatformTenantController.java`
- Create: `backend/src/main/java/com/medcloud/platform/tenant/api/HospitalProfileController.java`
- Create: `backend/src/main/java/com/medcloud/platform/identity/api/PatientProfileController.java`
- Test: `backend/src/test/java/com/medcloud/platform/tenant/api/RoleAuthorizationTest.java`
- Test: `backend/src/test/java/com/medcloud/platform/tenant/api/TenantIsolationTest.java`

**Interfaces:**
- Produces: `void requireTenant(CurrentUser user, UUID resourceTenantId)`。
- Produces: `GET /api/platform/tenants`, `GET /api/hospital/profile`, and `GET /api/patient/profile`。

- [ ] **Step 1: 写失败的角色矩阵测试**

Use real signed test tokens. Assert platform admin can list tenants, hospital admin and doctor can read only their hospital profile, patient can read only the patient profile, and every disallowed role receives 403.

- [ ] **Step 2: 写失败的跨租户测试**

```java
assertThatThrownBy(() -> policy.requireTenant(doctorA, tenantBId))
    .isInstanceOf(BusinessException.class)
    .extracting("code").isEqualTo(ErrorCode.TENANT_ACCESS_DENIED);
```

- [ ] **Step 3: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=RoleAuthorizationTest,TenantIsolationTest test`

Expected: FAIL because authorization controllers and policy are absent.

- [ ] **Step 4: 实现角色规则与租户策略**

Use `@PreAuthorize` for endpoint roles and `TenantAccessPolicy` for object-level tenant checks. Platform admin access is not a general bypass: only the platform tenant listing endpoint accepts that role.

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml -Dtest=RoleAuthorizationTest,TenantIsolationTest test`

Expected: PASS with all allowed and denied cases asserted.

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main backend/src/test
git commit -m "feat: enforce role and tenant access"
```

### Task 6: 建立后续业务模块契约

**Files:**
- Create: `backend/src/main/java/com/medcloud/platform/appointment/domain/AppointmentStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/consultation/domain/ConsultationCommand.java`
- Create: `backend/src/main/java/com/medcloud/platform/order/domain/OrderStatus.java`
- Create: `backend/src/main/java/com/medcloud/platform/file/application/FileAccessPort.java`
- Create: `backend/src/main/java/com/medcloud/platform/statistics/application/StatisticsQuery.java`
- Test: `backend/src/test/java/com/medcloud/platform/appointment/domain/AppointmentStatusTest.java`
- Test: `backend/src/test/java/com/medcloud/platform/order/domain/OrderStatusTest.java`

**Interfaces:**
- Produces: appointment states `BOOKED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` and allowed transition method `canTransitionTo`。
- Produces: order states `PENDING`, `PAID`, `CANCELLED` and allowed transition method `canTransitionTo`。
- Produces: ports that carry user ID, tenant ID and business object ID explicitly.

- [ ] **Step 1: 写失败的状态转换测试**

```java
assertThat(BOOKED.canTransitionTo(IN_PROGRESS)).isTrue();
assertThat(BOOKED.canTransitionTo(CANCELLED)).isTrue();
assertThat(COMPLETED.canTransitionTo(BOOKED)).isFalse();
assertThat(PENDING.canTransitionTo(PAID)).isTrue();
assertThat(PAID.canTransitionTo(CANCELLED)).isFalse();
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=AppointmentStatusTest,OrderStatusTest test`

Expected: FAIL because the state types do not exist.

- [ ] **Step 3: 实现最小状态机和公开契约**

Implement transitions with exhaustive enum switches. Do not add controllers, repositories or tables for unimplemented workflows. Define `ConsultationCommand`, `FileAccessPort` and `StatisticsQuery` as immutable records/interfaces with explicit tenant and actor identifiers.

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -f backend/pom.xml -Dtest=AppointmentStatusTest,OrderStatusTest test`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main backend/src/test
git commit -m "feat: define business module contracts"
```

### Task 7: 初始化 Vue 前端与认证状态

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/api/http.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/types/auth.ts`
- Test: `frontend/src/stores/auth.spec.ts`

**Interfaces:**
- Consumes: `POST /api/auth/login` and `GET /api/me` from Task 4.
- Produces: `useAuthStore()` with `login`, `restore`, `logout`, `isAuthenticated`, `role`, and `tenantId`。

- [ ] **Step 1: 创建前端配置和失败的认证 Store 测试**

Pin `vue@3.5`, `pinia@3`, `vue-router@4`, `axios@1`, `element-plus@2`, `vite@7`, `typescript@5`, `vitest@3`, `jsdom@26`, `@vue/test-utils@2` and `axios-mock-adapter@2`; configure Vitest with jsdom. Test observable behavior through the mock HTTP adapter:

```ts
it('stores the authenticated user after login', async () => {
  const auth = useAuthStore()
  await auth.login('doctor_a', 'Demo@123456')
  expect(auth.user?.role).toBe('DOCTOR')
  expect(auth.isAuthenticated).toBe(true)
})
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm --prefix frontend test -- --run src/stores/auth.spec.ts`

Expected: FAIL because the Store and API modules are absent.

- [ ] **Step 3: 实现最小认证状态和 HTTP 客户端**

Store access token in `sessionStorage`, not `localStorage`. Axios request interceptor adds `Authorization: Bearer <token>`; response interceptor clears the session only on 401. `restore()` calls `/api/me` and never infers authority from decoded client-side claims.

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `npm --prefix frontend test -- --run src/stores/auth.spec.ts`

Expected: PASS.

- [ ] **Step 5: 提交**

```powershell
git add frontend
git commit -m "feat: initialize Vue authentication state"
```

### Task 8: 实现登录页、路由守卫和角色工作台

**Files:**
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/layouts/AppLayout.vue`
- Create: `frontend/src/views/LoginView.vue`
- Create: `frontend/src/views/DashboardView.vue`
- Create: `frontend/src/views/ModulePlaceholderView.vue`
- Create: `frontend/src/navigation/roleMenus.ts`
- Test: `frontend/src/router/router.spec.ts`
- Test: `frontend/src/navigation/roleMenus.spec.ts`
- Test: `frontend/src/views/LoginView.spec.ts`

**Interfaces:**
- Consumes: `useAuthStore()` from Task 7.
- Produces: route metadata `{ requiresAuth: boolean, roles?: UserRole[] }` and `menusFor(role: UserRole)`。

- [ ] **Step 1: 写失败的路由与菜单测试**

Assert unauthenticated navigation redirects to `/login`; authenticated users cannot enter a route outside their role; each role gets exactly the menu group specified in the design document.

```ts
expect(menusFor('PATIENT').map(x => x.key)).toEqual([
  'dashboard', 'appointments', 'records-orders'
])
expect(menusFor('PLATFORM_ADMIN').map(x => x.key)).toEqual([
  'dashboard', 'tenants', 'platform-statistics'
])
```

- [ ] **Step 2: 写失败的登录页面测试**

Mount `LoginView`, enter username/password, submit, and assert successful login routes to `/dashboard`; assert API failure leaves the user on the page and renders the server message.

- [ ] **Step 3: 运行测试确认 RED**

Run: `npm --prefix frontend test -- --run`

Expected: FAIL because routes, pages and role menus are absent.

- [ ] **Step 4: 实现登录与主布局**

Build an accessible login form, loading state and error message. App layout shows project name, current user, tenant name when present, role-specific navigation and logout. Unimplemented modules render `ModulePlaceholderView` with a clear boundary description rather than fake data.

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `npm --prefix frontend test -- --run`

Expected: PASS for Store, router, menu and login tests.

- [ ] **Step 6: 提交**

```powershell
git add frontend
git commit -m "feat: add login and role workspaces"
```

### Task 9: 添加 Docker Compose 和开发文档

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/env.example`
- Create: `docs/development.md`
- Create: `README.md`
- Test: `backend/src/test/java/com/medcloud/platform/HealthEndpointTest.java`

**Interfaces:**
- Produces: MySQL service `mysql:3306`, MinIO service `minio:9000/9001`, named volumes, health checks and documented environment variables.

- [ ] **Step 1: 写失败的健康检查测试**

```java
mockMvc.perform(get("/actuator/health"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("UP"));
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -f backend/pom.xml -Dtest=HealthEndpointTest test`

Expected: FAIL if actuator exposure or security permission is not correctly configured.

- [ ] **Step 3: 完成运行配置与文档**

Compose pins MySQL 8 and MinIO images, uses named volumes, reads secrets from `.env`, and includes health checks. `env.example` contains non-secret sample variable names. Documentation lists prerequisites, environment preparation, Compose startup, backend startup, frontend startup, the four demo accounts, test commands and troubleshooting for ports 3306, 9000, 9001, 8080 and 5173.

- [ ] **Step 4: 验证配置与健康检查**

Run:

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/env.example config
mvn -f backend/pom.xml -Dtest=HealthEndpointTest test
```

Expected: Compose configuration renders successfully and the health test passes.

- [ ] **Step 5: 提交**

```powershell
git add deploy docs README.md backend/src/test
git commit -m "docs: add local development environment"
```

### Task 10: 全量验证和课程演示冒烟测试

**Files:**
- Modify: `README.md`
- Create: `docs/smoke-test.md`

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: repeatable verification evidence and a short manual demo script.

- [ ] **Step 1: 运行后端全量测试**

Run: `mvn -f backend/pom.xml clean test`

Expected: all context, migration, API, authentication, role, tenant-isolation, state and health tests pass with zero failures.

- [ ] **Step 2: 运行前端全量测试与构建**

Run:

```powershell
npm --prefix frontend test -- --run
npm --prefix frontend run build
```

Expected: all Vitest cases pass and TypeScript/Vite production build completes without errors.

- [ ] **Step 3: 验证容器配置与 MySQL 迁移**

Run:

```powershell
Copy-Item -LiteralPath deploy/env.example -Destination deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
mvn -f backend/pom.xml -Dspring-boot.run.profiles=dev spring-boot:run
```

Expected: MySQL and MinIO become healthy, backend applies both Flyway migrations, and `/actuator/health` returns `UP`. Stop the foreground backend after the check.

- [ ] **Step 4: 执行角色与租户冒烟测试**

Document and execute: four demo users log in; doctor and hospital admin see tenant A only; patient sees patient workspace; platform admin lists tenant A and B but has no medical-record endpoint; invalid, missing and cross-tenant credentials receive the expected 401 or 403 response.

- [ ] **Step 5: 检查敏感信息与工作区状态**

Run:

```powershell
rg -n "Demo@123456|JWT_SECRET|MINIO_SECRET_KEY" -g "!**/target/**" -g "!**/node_modules/**"
git status --short
```

Expected: development password appears only in demo migration/tests/docs, real secrets are absent, and only intentional project files remain.

- [ ] **Step 6: 提交**

```powershell
git add README.md docs/smoke-test.md
git commit -m "test: document foundation smoke test"
```
