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
