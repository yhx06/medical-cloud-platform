# 运维追踪与错误边界（R08 盘点）

本文盘点课程 PaaS 的请求关联、错误边界和日志脱敏约定，作为 OpenAPI（`course-openapi.yaml`）的运维配套。所有示例均为虚构，不含真实令牌、密码、JWT、临床正文或签名 URL。

## 1. 请求关联（Request-ID）

- **服务端生成**：`AuditRequestFilter`（`@Provider` `ContainerRequestFilter`）在每个入站请求上通过 `AuditRequestIds.ensure(request)` 生成或复用服务端 UUID；该 ID 通过 `ContainerRequestContext.setProperty` 挂在请求上，贯穿审计写入。
- **响应回传**：`AuditResponseFilter`（`ContainerResponseFilter`）把同一个 UUID 写入 `X-Request-ID` 响应头，调用方可据此关联日志。
- **异常回退**：`ApiFailureMapper.auditedRequestId` 在请求上下文不可用时，从 `AuditedOperationException` 异常链里取回已绑定的 requestId，保证错误响应与审计同源。
- **双入口**：`AuditRequestIds` 同时提供 `ContainerRequestContext` 与 RESTEasy `HttpRequest` 两个入口，覆盖 filter 与 resource/mapper 两种访问方式。

约束：入站请求不信任客户端自带的 request ID；客户端伪造的 `X-Request-ID` 不会进入服务端审计 ID。可信 ID 只在服务端生成一次。

## 2. 错误边界与业务码

`ApiFailureMapper.toResponse` 统一把异常映射为 `ApiError{code, message, requestId}` + HTTP 状态码：

| HTTP | 业务码 | 触发 |
|---|---|---|
| 400 | `BAD_REQUEST` | `IllegalArgumentException` |
| 400/409 | 医院/病历域异常自带码 | `HospitalAdministrationException` / `ClinicalSupplementException` |
| 401 | `UNAUTHORIZED` | `SecurityException` |
| 403 | `FORBIDDEN` | `AccessDeniedException` |
| 409 | `CONFLICT` / `APPOINTMENT_CONFLICT` | `IllegalStateException` / 锁超时 / 预约冲突 |
| 413/415/409/429 | 附件码（`FILE_TOO_LARGE` 等） | `AttachmentException` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `NotSupportedException`（二进制上传路由单独措辞） |
| 500 | `INTERNAL_ERROR` | 其余 |

- 错误响应**不回显 native 诊断**：500 一律返回 `Service temporarily unavailable`，服务端只记录 `requestId` 与事件类型，不落 SQL、异常堆栈、正文、密码、JWT 或签名 URL。
- EJB 包装会被 `unwrap` 解包后按原异常归类，保证 401/403/409 契约稳定。

## 3. 日志与脱敏

- 审计只记录操作事件、脱敏角色、结果、reason 码与 requestId，不提供患者身份、临床正文或明细入口。
- 500 日志仅 `requestId=<uuid> type=INTERNAL_ERROR`。
- 禁止记录：请求/响应正文、密码、JWT/access token、签名下载 URL、`Authorization` 头。

## 4. 跨层关联（门户 Tomcat → PaaS WildFly）

- 门户 `PaasClient` 只解析 API 错误信封，把稳定业务码映射为中文提示（`safeBusinessMessage`），不回显上游消息细节。
- 授权失效（401）在门户统一失效会话并回登录页；连接丢失/非 JSON 代理响应走 `PaasUnavailableException`，本地渲染「服务暂时不可用」。

## 5. 测试

- `ApiFailureTest`：401/403/409/415 稳定码、EJB 解包、附件词汇、以及 500 不回显 SQL/密码但保留 requestId 的反例。
- `validate-openapi.py`：把 `course-openapi.yaml` 与源码 JAX-RS 路由逐一比对（58/58），缺失或多余即非零退出。
