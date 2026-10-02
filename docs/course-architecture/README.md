# 课程版技术架构与服务契约

```text
浏览器 → Tomcat 9 /portal（Servlet、JSP、角色门面）
       → HTTP/JSON → WildFly 10 /paas/api（JAX-RS、EJB 业务服务）
                   → JPA → java:/jdbc/MedicalCloudCourseDS → MySQL
```

Tomcat 保留登录会话并提供患者、医生和医院操作页面，不直连数据库。`CloudServiceFactory` 按角色选择门户门面；`PaasClient` 设置 3 秒连接与 8 秒读取超时。WildFly 中的认证、机构管理、号源、预约、接诊和支付服务通过 EJB/JPA 事务持久化。登录令牌使用 HMAC-SHA256 签名，每次受保护请求仍从数据库重读账号状态、角色与机构。可编辑 [PowerDesigner 物理模型](medical-cloud-course.pdm) 与 [关系图](medical-cloud-er.png)包含最终 8 张表、14 条外键、主键、唯一键和 3 个普通索引。PNG 从原生矢量导出渲染并保留边距，全部字段可读；`.pdb` 是 PowerDesigner 保存时生成的上一版备份，保留在本地并忽略提交。

## HTTP 接口目录

所有路径以下列前缀为基准：`http://127.0.0.1:8080/paas/api`。正常响应使用 `{ "code": "OK", "message": "...", "data": ... }`；失败响应包含 `code`、公共 `message` 和 `requestId`。除注册、登录和号源目录外，发送 `Authorization: Bearer <令牌>`。

| 方法与路径 | 用途 | 调用者 |
| --- | --- | --- |
| `POST /auth/login` | 登录、签发令牌 | 全部账号 |
| `POST /auth/register` | `{ "username": "...", "password": "..." }`，只创建患者 | 公开注册 |
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

空体 POST 仍需携带 `Content-Type: application/json`，可发送 `{}`。停用账号登录返回 401；越权接诊返回 403；重复预约或重复完成接诊返回 409。模拟缴费的幂等性不等于真实支付网关的安全性。

已登录但角色不符的预约/订单操作返回 403。目录与预约写入均按上海时间检查开诊截止；预约在锁内重验科室状态与医生当前科室，不能使用缓存的旧号源编号绕过目录筛选。预约按科室→医生→号源→预约顺序加锁；修改号源也按此顺序用当前读判断已提交预约，避免 MySQL REPEATABLE READ 的旧快照放行撤回。

接诊完成请求另可提交 `complaint`、`notes`；开始、完成和支付时间由服务端记录。课程日历与取消截止按 `Asia/Shanghai`，新预约使用微秒级 `booked_at` 排序；旧记录的未知时间保持 NULL，不伪造时间。医院订单路由位于 `HospitalResource`，避免 WildFly 10 的 RESTEasy 优先匹配 `/hospital` 后忽略根资源下的订单方法。

## 数据模型与分层对应

- IaaS：计算、网络、存储及虚拟化资源，见 [70 台逻辑服务器模型](medical-cloud-iaas-generated.eam)与[实验绘图说明](powerdesigner-iaas-lab-guide.md)。该模型用于课程建模，70 台服务器未实际部署。
- PaaS：WildFly/EJB/JPA 与 MySQL 数据服务。`schema.sql` 定义上述 8 张业务表；EJB 在事务内执行预约锁定、接诊、病历和订单写入，身份由令牌与数据库关系共同确认。
- SaaS：Tomcat 提供患者注册/预约/取消/缴费、医生接诊/历史病历、医院科室/医生/号源/订单页面。平台页用于课程架构展示，监管统计尚未实现。

课程版的重点是可在教师 JDK 8 与双中间件环境中核查的纵向业务切片。原 Java 21/Vue 版本保留为另一个实现，不属于这个 WAR 的依赖链。
