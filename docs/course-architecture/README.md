# 课程版技术架构与服务契约

```text
浏览器 → Tomcat 9 /portal（Servlet、JSP、角色门面）
       → HTTP/JSON → WildFly 10 /paas/api（JAX-RS、EJB 业务服务）
                   → JPA → java:/jdbc/MedicalCloudCourseDS → MySQL
```

Tomcat 保留登录会话并提供患者、医生和医院操作页面，不直连数据库。`CloudServiceFactory` 按角色选择门户门面；`PaasClient` 设置 3 秒连接与 8 秒读取超时。WildFly 中的认证、机构管理、号源、预约、接诊和支付服务通过 EJB/JPA 事务持久化。登录令牌使用 HMAC-SHA256 签名，每次受保护请求仍从数据库重读账号状态、角色与机构。可编辑 [PowerDesigner 物理模型](medical-cloud-course.pdm) 与 [关系图](medical-cloud-er.png)包含最终 8 张表、14 条外键、主键、唯一键和 3 个普通索引。PNG 从原生矢量导出渲染并保留边距，全部字段可读；`.pdb` 是 PowerDesigner 保存时生成的上一版备份，保留在本地并忽略提交。

## HTTP 接口目录

所有路径以下列前缀为基准：`http://127.0.0.1:8080/paas/api`。正常响应使用 `{ "code": "OK", "message": "...", "data": ... }`；失败响应包含 `code`、公共 `message` 和 `requestId`。除注册、登录和公开挂号目录外，发送 `Authorization: Bearer <令牌>`。

| 方法与路径 | 用途 | 调用者 |
| --- | --- | --- |
| `POST /auth/login` | 登录、签发令牌 | 全部账号 |
| `POST /auth/register` | `{ "username": "...", "password": "..." }`，只创建患者 | 公开注册 |
| `GET /catalog/hospitals` | 启用医院的 `id/name` 列表 | 公开目录 |
| `GET /catalog/departments?tenantId=...` | 必填医院；返回该启用医院的启用科室 `id/name` | 公开目录 |
| `GET /catalog/doctors?tenantId=...&departmentId=...` | 必填医院，科室可选；返回启用且机构/科室/角色关联有效的医生 `id/name` | 公开目录 |
| `GET /catalog/slots?tenantId=...&departmentId=...&doctorId=...&date=YYYY-MM-DD` | 必填机构；其他筛选可选；返回尚未开诊且有余量的号源 | 公开目录 |
| `GET/POST /hospital/departments`、`PUT /hospital/departments/{id}` | 本机构科室列表、创建、修改与停用 | 医院管理员 |
| `GET/POST /hospital/doctors`、`PUT /hospital/doctors/{id}` | 本机构医生列表、创建及修改；创建返回一次性初始密码 | 医院管理员 |
| `GET/POST /hospital/slots`、`PUT /hospital/slots/{id}` | 本机构号源列表、创建、修改/发布；接收 departmentId、doctorId、date、startTime、endTime、capacity、fee、status | 医院管理员 |
| `POST /patient/appointments` | 预约 `{ "slotId": "..." }` | 患者 |
| `GET /patient/appointments` | 本人预约，按预约时间倒序 | 患者 |
| `POST /patient/appointments/{id}/cancel` | 开诊前取消本人待诊预约并释放余量，可重新预约 | 患者 |
| `GET /doctor/appointments` | 查询本人预约 | 医生 |
| `POST /doctor/appointments/{id}/start` | 开始接诊 | 该预约所属医生 |
| `POST /doctor/appointments/{id}/complete` | 提交 `{ "diagnosis": "...", "advice": "..." }`，同事务生成病历与账单 | 该预约所属医生 |
| `GET /doctor/records` | 本医生、本机构的历史病历 | 医生 |
| `GET /patient/appointments/{id}/record` | 查看本人病历 | 该患者 |
| `GET /patient/appointments/{id}/order` | 查看本人账单 | 该患者 |
| `POST /patient/orders/{id}/pay` | 模拟缴费，重复请求返回同一已支付订单 | 该患者 |
| `GET /hospital/orders?status=PAID&from=YYYY-MM-DD&to=YYYY-MM-DD` | 本机构订单；可选状态与支付日期筛选，最多最近 100 条 | 医院管理员 |
| `GET /platform/tenants?page=1&size=20` | 医院 ID/名称/状态分页列表 | 平台管理员 |
| `PUT /platform/tenants/{id}/status` | `{ "status": "DISABLED", "expectedStatus": "ACTIVE" }`，仅允许这两个字段；并发状态冲突返回 409 | 平台管理员 |
| `GET /hospital/context`、`GET /doctor/context` | 本机构状态与 `businessWritable` 提示，不替代写服务校验 | 对应医院管理员/医生 |
| `GET /hospital/statistics?from=YYYY-MM-DD&to=YYYY-MM-DD` | 仅本机构汇总；任何 `tenantId` 参数返回 400 | 医院管理员 |
| `GET /platform/statistics?from=YYYY-MM-DD&to=YYYY-MM-DD` | 跨医院总计、分院、每日趋势和医院存量计数 | 平台管理员 |

空体 POST 仍需携带 `Content-Type: application/json`，可发送 `{}`。停用账号登录返回 401；越权接诊返回 403；重复预约或重复完成接诊返回 409。模拟缴费的幂等性不等于真实支付网关的安全性。

公开挂号目录不返回登录账号、密码哈希、患者或病历信息。门户以该目录生成医院—科室—医生下拉选项，并将号源编号映射为科室与医生名称；号源接口的原字段和构造契约不变。更换上级选择通过只读 GET 更新选项，禁用脚本时使用“更新选项”按钮；正常号源查询不会因陈旧或伪造的下级筛选改成不带筛选的查询。完成预约可直接进入本人病历与账单，快捷入口和手动查询保留当前挂号筛选，读取权限仍由 PaaS 校验。交互验证见 [2026-10-03 验收记录](../course-labs/2026-10-03-interaction-acceptance.md)。

已登录但角色不符的预约/订单操作返回 403。目录与预约写入均按上海时间检查开诊截止；预约在锁内重验科室状态与医生当前科室，不能使用缓存的旧号源编号绕过目录筛选。预约锁序为医院→科室→医生→号源→预约；修改号源也以医院锁为前缀、按相关实体次序使用当前读判断已提交预约，避免 MySQL REPEATABLE READ 的旧快照放行撤回。所有业务写入口先持医院读锁并 refresh 状态，平台启停持医院写锁直到事务提交；已持业务锁的写可先提交，停用提交后旧 ACTIVE 快照也不能继续写。

接诊完成请求另可提交 `complaint`、`notes`；开始、完成和支付时间由服务端记录。课程日历与取消截止按 `Asia/Shanghai`，新预约使用微秒级 `booked_at` 排序；旧记录的未知时间保持 NULL，不伪造时间。医院订单路由位于 `HospitalResource`，避免 WildFly 10 的 RESTEasy 优先匹配 `/hospital` 后忽略根资源下的订单方法。

医院 context/statistics 也归属 `HospitalResource`，医生 context 和平台 statistics 各有明确类级路径，实际 RESTEasy 路由已回归。停用医院不修改账号状态；有效账号原会话和新登录仍能读取既有权限内历史，公开目录隐藏停用医院。预约、取消、开始/完成、未付/重复已付支付及科室/医生/号源 create/update 全部返回 409。重新启用只解除医院级冻结，不自动启用独立停用科室、账号或撤回号源。平台角色不能读取医院订单或患者病历。

## 统计口径与事务

`from/to` 去除空白后严格接受真实 `yyyy-MM-dd`，首尾包含；同时缺省为上海当天及前 29 天，单端缺省、倒置或超过 366 天返回 400。无业务日期填零；金额由数据库 DECIMAL 与 Java BigDecimal 累加，页面显示人民币元两位小数。

预约 cohort 按下单日期 `booked_on` 分组，不按号源就诊日期 `slotDate`；四个状态计数表示该 cohort 查询时当前状态，不是状态转换当日数量。接诊开始、病历完成、模拟实付分别按真实 `started_at/completed_at/paid_at` 的上海日期归属；订单只在接诊完成时生成，其数量/应收金额按同租户同预约关联病历的 `completed_at` 归属。跨日事件可属于不同日期。旧 NULL 时间不回填、不归入事件趋势，在全量 `legacyUnknownTime` 单列数量/金额，不受所选日期区间筛选。平台 `tenantCounts` 是快照中的全体医院存量状态，不是日期区间新增数。

每份报告在同一 REQUIRED EJB/JTA 事务、经确认的 MySQL REPEATABLE READ 一致读快照内完成全部聚合。每日、分院和总计来自同一快照；报告仅含白名单汇总、医院 ID/名称/状态与元数据，无患者身份、诊断/主诉/建议/备注或明细 ID。门户统计筛选 `statsFrom/statsTo` 与订单筛选分离；分区读取失败显示错误，不伪造零趋势，401 清除会话。实际证据及未注入场景见 [平台统计验收](../course-labs/2026-10-03-platform-statistics-acceptance.md)。

## 数据模型与分层对应

- IaaS：计算、网络、存储及虚拟化资源，见 [70 台逻辑服务器模型](medical-cloud-iaas-generated.eam)与[实验绘图说明](powerdesigner-iaas-lab-guide.md)。该模型用于课程建模，70 台服务器未实际部署。
- PaaS：WildFly/EJB/JPA 与 MySQL 数据服务。`schema.sql` 定义上述 8 张业务表；EJB 在事务内执行预约锁定、接诊、病历和订单写入，身份由令牌与数据库关系共同确认。
- SaaS：Tomcat 提供患者注册/预约/取消/缴费、医生接诊/历史病历、医院科室/医生/号源/订单/本院统计页面；平台页提供医院启停、跨院脱敏统计与课程架构展示。

课程版的重点是可在教师 JDK 8 与双中间件环境中核查的纵向业务切片。原 Java 21/Vue 版本保留为另一个实现，不属于这个 WAR 的依赖链。
