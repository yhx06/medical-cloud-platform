# 课程版技术架构与服务契约

```text
浏览器 → Tomcat 9 /portal（Servlet、JSP、角色门面）
       → HTTP/JSON → WildFly 10 /paas/api（JAX-RS、EJB 业务服务）
                   → JPA → java:/jdbc/MedicalCloudCourseDS → MySQL
```

Tomcat 只保留登录会话和角色导航，不直连数据库。`CloudServiceFactory` 按患者、医生、医院管理员、平台管理员选择门户门面；`PaasClient` 设置 3 秒连接与 8 秒读取超时。WildFly 中的 `AuthService`、`BookingService`、`ClinicalService`、`PaymentService` 是 EJB 业务组件，JPA 负责事务和持久化。登录令牌使用 HMAC-SHA256 签名，但每次受保护请求仍从数据库重读账号状态、角色与机构。可编辑 PowerDesigner 物理模型为 [medical-cloud-course.pdm](medical-cloud-course.pdm)，共有 8 张表、14 条外键；[导出的关系图](medical-cloud-er.png)用于课堂概览，边缘部分可能被 PowerDesigner 导出画布裁切，以 `.pdm` 为准。

## HTTP 接口目录

所有路径以下列前缀为基准：`http://127.0.0.1:8080/paas/api`。正常响应使用 `{ "code": "OK", "message": "...", "data": ... }`；失败响应包含 `code`、公共 `message` 和 `requestId`。除登录和号源目录外，发送 `Authorization: Bearer <令牌>`。

| 方法与路径 | 用途 | 调用者 |
| --- | --- | --- |
| `POST /auth/login` | 登录、签发令牌 | 全部账号 |
| `GET /catalog/slots?tenantId=...` | 查询某机构未过期且有余量的号源 | 公开目录 |
| `POST /patient/appointments` | 预约 `{ "slotId": "..." }` | 患者 |
| `GET /doctor/appointments` | 查询本人预约 | 医生 |
| `POST /doctor/appointments/{id}/start` | 开始接诊 | 该预约所属医生 |
| `POST /doctor/appointments/{id}/complete` | 提交 `{ "diagnosis": "...", "advice": "..." }`，同事务生成病历与账单 | 该预约所属医生 |
| `GET /patient/appointments/{id}/record` | 查看本人病历 | 该患者 |
| `GET /patient/appointments/{id}/order` | 查看本人账单 | 该患者 |
| `POST /patient/orders/{id}/pay` | 模拟缴费，重复请求返回同一已支付订单 | 该患者 |

空体 POST 仍需携带 `Content-Type: application/json`，可发送 `{}`。停用账号登录返回 401；越权接诊返回 403；重复预约或重复完成接诊返回 409。模拟缴费的幂等性不等于真实支付网关的安全性。

## 数据模型与分层对应

- IaaS：`tenant`、`department`、`user_account`、`doctor_profile`、`schedule_slot`、`appointment`、`clinical_record`、`payment_order`，通过 `schema.sql` 创建。
- PaaS：EJB 在数据库事务内执行预约锁定、接诊、病历和订单写入；医生及患者身份从令牌与数据库关系共同确认，不能信任前端传入的机构 ID。
- SaaS：Tomcat 提供患者、医生、医院和平台页面。医院与平台页目前主要用于课程架构/目录展示，尚不提供完整机构运营或脱敏统计能力。

课程版的重点是可在教师 JDK 8 与双中间件环境中核查的纵向业务切片。原 Java 21/Vue 版本保留为另一个实现，不属于这个 WAR 的依赖链。
