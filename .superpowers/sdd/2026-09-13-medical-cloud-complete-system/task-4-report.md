# Task 4 实施报告：并发安全的患者预约

## 状态

- 完成：患者预约、取消、本人查询，医生本人且本租户查询，容量串行化、有效预约唯一约束、号源目录余量计算。
- 基线：开始前 `mvn -f backend/pom.xml test` 为 97 tests / 0 failures / 0 errors / 0 skipped。

## 实现

- V6 新增 `appointment` 表，持久化租户、患者、医生、科室、号源、日期、起止时间和诊费快照。
- `active_patient_id` 是由状态生成的列：非 `CANCELLED` 时取 `patient_id`，取消时为 `NULL`；`(slot_id, active_patient_id)` 唯一键阻止同患者同号源存在两条有效预约，同时允许取消后重约。
- 预约事务先以 `PESSIMISTIC_WRITE` 锁定 `schedule_slot`，再依次检查发布状态/日期、重复预约和非取消预约数量；重复映射为 `APPOINTMENT_DUPLICATE`，满号映射为 `SCHEDULE_FULL`，唯一约束竞态也按约束名映射为 `APPOINTMENT_DUPLICATE`。
- 预约实体从已锁定号源及认证患者复制不可更新的归属、日期/时间和费用字段；请求体只接受 `slotId`。
- 取消仅允许本人 `BOOKED` 预约；先确定本人预约，再按同一顺序锁定 slot，状态更新为 `CANCELLED` 后生成列释放唯一键。
- 患者列表/详情/取消始终包含 `patient_id` 条件；医生列表同时包含 JWT 中的 `doctor_id` 和 `tenant_id` 条件；患者/医生控制器分别做角色限制，平台管理员不能借用这些查询。
- 号源目录的 `remainingCapacity` 改为 `capacity - count(status <> CANCELLED)`。

## 文件

- 新增：`V6__appointment.sql`、`Appointment.java`、`AppointmentRepository.java`、`AppointmentService.java`、`PatientAppointmentController.java`、`DoctorAppointmentController.java`。
- 修改：`AppointmentStatus.java`、`ErrorCode.java`、`ScheduleSlotRepository.java`、`ScheduleService.java`。
- 测试：`PatientAppointmentTest.java`、`AppointmentCapacityTest.java`。

## TDD RED 证据

1. `mvn -f backend/pom.xml -Dtest=PatientAppointmentTest test`
   - 生产接口尚不存在时：4 tests / 4 failures；预约请求期望 200，实际由缺失 handler 返回 500 `INTERNAL_ERROR`。
2. `mvn -f backend/pom.xml '-Dtest=PatientAppointmentTest,AppointmentCapacityTest' test`
   - 生产类型尚不存在时按预期 test compilation failure：缺少 `AppointmentService`、`AppointmentRepository`、`SCHEDULE_FULL` 等，共 7 个编译错误。
3. `mvn -f backend/pom.xml -Dtest=AppointmentCapacityTest test`
   - 在有真实事务但尚未加入 slot 悲观锁的中间实现上：1 test / 1 failure；容量为 1 时两个独立事务都成功，关键输出为 `Expected size: 1 but was: 2`，明确复现超卖。
4. `mvn -f backend/pom.xml -Dtest=PatientAppointmentTest test`
   - 查询、可用性和余量实现前：8 tests / 4 failures；分别捕获 patient/doctor GET 缺失（500）、停用号源仍预约成功（期望 409、实际 200）、余量期望 1 实际 2。

## GREEN 与验证

- API focused：`mvn -f backend/pom.xml -Dtest=PatientAppointmentTest test` -> 9 tests / 0 failures / 0 errors / 0 skipped。
- 并发 focused：`mvn -f backend/pom.xml -Dtest=AppointmentCapacityTest test` -> 1 test / 0 failures / 0 errors / 0 skipped；同一容量 1 号源只提交 1 条，另一事务得到 `SCHEDULE_FULL`。
- brief focused：`mvn -f backend/pom.xml '-Dtest=PatientAppointmentTest,AppointmentCapacityTest,AppointmentStatusTest' test` -> 11 tests / 0 failures / 0 errors / 0 skipped。
- 完整后端：`mvn -f backend/pom.xml test` -> 107 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`，总耗时 58.956 s（2026-09-15 18:48:42 +08:00）。
- `git diff --check` 与 `git diff --cached --check` 均无空白错误；Git 仅提示仓库既有的 LF/CRLF 转换警告。

## MySQL smoke

- 尝试命令：`docker version --format '{{.Server.Version}}'`
- 状态：未能启动 MySQL 容器 smoke，因为本机 Docker Engine 不可用。
- 原始错误：`error during connect: Get "http://%2F%2F.%2Fpipe%2FdockerDesktopLinuxEngine/v1.51/version": open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.`
- 未修改 Docker 或系统配置。H2 MySQL 模式已实际执行 V6；真实 Spring service/repository、两个线程及独立事务的 RED/GREEN 并发测试已完成。

## 自审

- 并发：删除 `@Lock(PESSIMISTIC_WRITE)` 的真实中间版本已经由同一测试稳定捕获为 2 条成功预约；测试中的 H2 trigger 只用于强制交错，不参与生产同步。
- 唯一性：服务层重复检查外，测试直接插入第二条有效预约并观察 `uk_appointment_active_booking` 约束失败；取消后重约测试证明生成列释放键。
- 快照：测试向预约请求伪造患者/租户/医生/科室/日期/时间/费用仍无效，并在预约后修改 slot，预约详情仍返回原始日期、时间和费用。
- 隔离：患者列表、详情和取消均验证跨患者不可见；医生查询用同租户其他医生和伪造租户 JWT 两类反例分别证明 doctor 与 tenant 两个条件都不可省略；平台管理员访问两类查询均为 403。
- 状态与余量：仅 `BOOKED` 可取消；`IN_PROGRESS` 取消为 `APPOINTMENT_STATE_INVALID`；目录分别验证预约后减 1、取消后恢复。

## Concerns

- 唯一剩余验证缺口是无法在当前主机运行真实 MySQL 容器，因此 MySQL 上的 V6 DDL 和锁语义未做本机 smoke；H2 MySQL 模式迁移、数据库唯一约束及跨事务并发均已通过。

## Fix Round 1：并发取消与重复预约语义

### 根因与修复

- 两个取消事务可以在竞争 slot 锁之前，把同一条 `BOOKED` Appointment 载入各自的 persistence context；获得 slot 锁后的普通重复查询仍可能返回受管缓存中的陈旧实体，导致两个事务都通过 `BOOKED` 状态校验。
- 保持全流程的锁顺序为 slot → appointment。在获得共享 slot 的 `PESSIMISTIC_WRITE` 锁后，使用 `EntityManager.refresh(initial, PESSIMISTIC_WRITE)` 对已受管 Appointment 执行数据库当前读并取得写锁，再执行领域取消操作。

### RED / GREEN 证据

1. 并发取消 RED：
   - `mvn -f backend/pom.xml '-Dtest=AppointmentCapacityTest#concurrentCancellationsRefreshStateAfterTheSharedSlotLock' test`
   - 修复前 1 test / 1 failure；两个独立事务均成功，关键输出：`Expected size: 1 but was: 2`，两个 `CancellationAttempt` 的 `errorCode` 都为 `null`。
2. 并发取消 GREEN：
   - 同一聚焦命令修复后 1 test / 0 failures / 0 errors / 0 skipped。
   - 恰好一个事务取消成功，另一个稳定返回 `APPOINTMENT_STATE_INVALID`，最终状态为 `CANCELLED`。
3. 同患者同号源并发：
   - `mvn -f backend/pom.xml '-Dtest=AppointmentCapacityTest#concurrentBookingsByTheSamePatientReturnDuplicateAfterTheSharedSlotLock' test`
   - 新测试在现有 slot 行锁实现上首次即 GREEN：1 test / 0 failures / 0 errors / 0 skipped；一个事务成功，另一个稳定返回 `APPOINTMENT_DUPLICATE`，最终仅一条有效预约。该结果符合预期，未为制造 RED 而弱化生产锁实现。

### 回归验证

- `mvn -f backend/pom.xml '-Dtest=AppointmentCapacityTest' test` -> 3 tests / 0 failures / 0 errors / 0 skipped。
- `mvn -f backend/pom.xml test` -> 109 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`（2026-09-15 19:19:10 +08:00）。

### MySQL 8.4 smoke 重试

- 再次执行 `docker version`，客户端可用，但 Linux engine 管道仍不存在：`open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.`
- 已从 `C:\Program Files\Docker\Docker\Docker Desktop.exe` 隐藏启动 Docker Desktop，并累计轮询约 75 秒；引擎仍未就绪，Docker Desktop 进程已退出。
- `wsl.exe --status` 报告当前机器配置不支持 WSL2，并提示启用硬件虚拟化；`com.docker.service` 为 stopped，当前会话无法启动该服务。
- 因 Docker engine 的宿主环境前置条件不满足，无法创建明确命名的临时 MySQL 8.4 容器，故本轮没有容器可清理，也未声称 MySQL smoke 通过。H2 MySQL 模式已覆盖 V6、不同患者容量竞争、同患者重复和并发取消。

## Fix Round 2：GitHub Actions MySQL 8.4 smoke

### Workflow

- 新增 `.github/workflows/mysql-appointment-smoke.yml`，提供 `workflow_dispatch`，并用仅限 `feature/complete-system-implementation` 且仅匹配 Task 4 相关路径的 `push` 触发完成首次注册。
- job 使用 GitHub Actions Linux runner 的 `mysql:8.4` service，配置健康检查、15 分钟超时和只读仓库权限；Java 固定为 Temurin 21。
- 测试命令：`mvn -f backend/pom.xml -Dtest=AppointmentCapacityTest test`。
- 通过 `SPRING_DATASOURCE_URL`、username、password 及 `SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver` 覆盖测试默认 H2 datasource。测试源码已有数据库产品判断，H2 专属 trigger 创建/删除不会在 MySQL 路径执行，因此没有新增 profile 或修改默认 H2 配置。

### 静态与 H2 回归

- PyYAML 解析成功；`npx --yes prettier@3.6.2 --check .github/workflows/mysql-appointment-smoke.yml` 通过；`git diff --check` 无空白错误。
- `mvn -f backend/pom.xml '-Dtest=AppointmentCapacityTest' test` -> 3 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`。这确认 CI datasource 覆盖未改变默认 H2 路径。

### Actions 运行证据

- 首次注册 push run：`34964576430`，成功；URL：`https://github.com/yhx06/medical-cloud-platform/actions/runs/34964576430`。
- 手动触发命令：`gh workflow run mysql-appointment-smoke.yml --ref feature/complete-system-implementation`。
- 验收 run：`34964637671`，event=`workflow_dispatch`，head SHA=`d9c7af28aba7ffea8b49ad045360070422756e0c`，status=`completed`，conclusion=`success`。
- URL：`https://github.com/yhx06/medical-cloud-platform/actions/runs/34964637671`。
- 容器日志确认拉取并创建 `mysql:8.4`；Flyway 识别 `MySQL 8.4`，依次执行 V1、V2、V3、V4、V5、V6，并报告 `Successfully applied 6 migrations ... now at version v6`。
- `AppointmentCapacityTest` -> 3 tests / 0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`，测试耗时 12.46 s。三项分别覆盖不同患者抢容量（1 成功 + 1 `SCHEDULE_FULL`）、同患者同号源（1 成功 + 1 `APPOINTMENT_DUPLICATE`）及并发取消（1 成功 + 1 `APPOINTMENT_STATE_INVALID`）。

### RED / GREEN 与自审

- Round 1 的本机 RED 是 Docker Linux engine 因 WSL2/虚拟化不可用而无法创建 MySQL 容器；本轮将相同验证迁移到具备 Docker 能力的 GitHub Actions Linux runner，无需修改用户 BIOS/WSL。新增 workflow 的首次 push run 与后续手动 run 均直接 GREEN，因此没有生产代码或测试逻辑的猜测性修改。
- workflow 只运行 Task 4 的三项 Spring/JPA 真并发测试，既验证服务/仓储锁语义，也由 Spring 启动阶段实际执行 Flyway V1-V6；不是仅做 SQL 静态检查。
- MySQL service 在独立 runner job 生命周期内创建并由 Actions 的 `Stop containers` 步骤成功清理，不持久化测试数据或暴露外部数据库。

### Concerns

- 无 Task 4 剩余验证缺口。该 workflow 有意保持窄范围：实施分支相关路径 push 或手动触发，不替代完整后端 CI。
