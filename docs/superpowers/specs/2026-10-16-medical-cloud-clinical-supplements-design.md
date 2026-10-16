# 医疗云课程版：病情补充、接诊草稿与病历更正设计

日期：2026-10-16。课程版基线 `codex/course-environment` / `aae787e`。

状态：用户已确认本文具体设计并要求编写实施计划，沿用多智能体执行方式。产品实施仍须等待实施计划审阅；附件 Task8 验收独立继续，不因本文产生新的功能完成声明。

## 1. 目标与范围

补齐 FR-APT-05 患者病情描述及修改时间、FR-CON-05 更正前后内容/操作者/可追溯，以及既有完整设计的接诊草稿。形成患者补充→原医生私有草稿→完成并冻结原始病历→追加更正→患者查看历史的课程演示闭环。

用户已确认：患者在预约未完成时可补充病情；医生草稿仅本人可见，不产生订单；完成后的原始病历冻结；更正保留前后版本、原因、操作者和时间，患者能查看更正历史；订单与诊费不变。本设计将“未完成可写”明确为 BOOKED/IN_PROGRESS，不包括 CANCELED。

沿用 Java8、Tomcat9 Servlet/JSP、WildFly10 JAX-RS/EJB/JPA/JNDI、MySQL 双 WAR 路线。所有数据虚构/脱敏，不制作 PowerDesigner，不启动70台服务器。不包含处方、订单取消、机构账号开通、AI/评价、真实医疗或支付对接；这些保留在全系统范围，不被删除。

## 2. 方案比较与选择

采用独立草稿表和追加式结构化更正表，保留现有 ClinicalRecord 原始快照及附件关联。患者描述放在预约中，与医生记录的 complaint 区分来源。优点是旧病历、订单、附件 ID 和原始查询语义保持稳定；成本是两张新表及预约增量列，需要受保护迁移。

不采用将 ClinicalRecord 改成 DRAFT/COMPLETED 的单表方案：它改变“未完成无正式病历”的既有接口和附件语义，回归面明显更大。也不采用统一事件溯源表：会引入本切片不需要的投影和恢复体系。只保存自由文本更正原因不足以证明更正前后内容，因此不作为简化方案。

## 3. 状态、权限与内容规则

| 操作 | 主体与状态 | 医院冻结后的行为 |
| --- | --- | --- |
| 读本人病情描述 | 启用患者本人；原分配医生通过工作区读取 | 可读历史 |
| 修改病情描述 | 启用患者本人；BOOKED/IN_PROGRESS | 禁止 |
| 读/保存接诊草稿 | 启用原医生本人本院；保存仅 IN_PROGRESS | 可读已存草稿，禁止保存 |
| 完成接诊 | 启用原医生本人本院；IN_PROGRESS | 禁止 |
| 追加更正 | 启用原医生本人本院；已有 COMPLETED 病历 | 禁止 |
| 读原始/当前有效病历与更正历史 | 启用本人患者或原医生本人本院 | 可读历史 |

医院及平台管理员不能读取患者描述、草稿、病历或更正正文，只有既有脱敏审计权限。令牌身份、实际账号 ACTIVE、数据库医院/患者/医生/预约/病历关联必须逐次校验；不能信任客户端传来的 tenantId/doctorId/patientId。无权与不存在统一拒绝，不泄露资源是否存在。

患者描述最长500字符；允许显式清空为 null，发生有效变化时保存服务端修改时间。医生四字段长度沿用现有模型：complaint/diagnosis各500、advice/notes各2000字符；以 Unicode 码点计数并拒绝 NUL/非法代理字符，允许正常换行。草稿允许未填写完整，完成和更正的 diagnosis/advice 必填。Portal 完成时主诉仍要求填写；旧API在没有草稿时允许缺省 complaint 的兼容规则不改变，历史空值不补造。

患者描述不自动覆盖医生草稿/病历。医生页面单独显示“患者自述”和修改时间，可明确选择复制到医生主诉再编辑；复制后不是实时绑定。患者不能读取草稿，只有正式原始病历、有效更正和更正历史可读。

## 4. 持久化与版本

预约增加 patient_complaint VARCHAR(500) NULL、complaint_updated_at DATETIME(6) NULL、complaint_revision BIGINT NOT NULL DEFAULT 0。旧数据保持 null/null/0，不人为填入历史修改时间。每次有效变化版本+1；无变化不改时间/版本。

新增 clinical_draft：UUID id，唯一 appointment_id，tenant_id/doctor_id/patient_id，complaint/diagnosis/advice/notes 四字段（允许 null），saved_at DATETIME(6)，revision BIGINT。无草稿读取版本0，首次保存版本1。版本为业务并发令牌，不让客户端指定最终值。完成接诊同事务删除草稿，已有原始 ClinicalRecord 成为正式来源；不保留私有草稿当作患者历史。

新增 record_correction：UUID id，record_id/appointment_id/tenant_id/doctor_id/patient_id，sequence_number，before/after 各四个字段，reason VARCHAR(2000) NOT NULL，created_at DATETIME(6)，operation_key VARCHAR(36)。before 全部由服务端从上一有效版本取值，客户端只提交完整 after。原始为版本0，首更正为1，后续递增；唯一(record_id,sequence_number)、唯一(doctor_id,operation_key)。所有更正只追加，不提供修改/删除接口。

两表使用 InnoDB/utf8mb4、标量UUID外键、无级联删除；类型兼容 MySQL5.6，不用 JSON、CHECK 或8.0专有语法。索引覆盖预约唯一草稿、record_id/sequence_number分页、原医生幂等键；迁移中核算 utf8mb4 索引长度。JPA 主/测试 persistence.xml 显式登记实体，生产仍 validate，禁止自动建表。

原始 clinical_record、payment_order 的任何字段不因草稿或更正而修改；file_metadata.record_id 继续指向原始病历。更正不重新完成接诊、不重新生成订单、不改变诊费、支付状态、完成时间或支付时间。

## 5. 事务、锁及重试

保持父资源在前：医院→医生账号→患者账号（患者写适用）→号源→预约→草稿/原病历/更正→成功审计。所有写操作在锁定并 refresh 父资源后重新验证状态和归属；不得先锁更正/病历再反向取预约锁。患者账号的 tenant_id 可以为空，不用其替代预约医院。

病情修改和草稿保存要求 expectedRevision（非负整数）。两标签页基于同版本写：仅一个有效更改成功，另一返回409；相同版本且内容未变返回原版本/原时间并记录 NO_CHANGE。过期版本即使正文相同也冲突，不静默覆盖。

新完成请求提交完整四字段和 expectedDraftRevision。只使用本次提交内容，不将缺省字段与数据库草稿暗中合并。草稿存在时必须携带匹配版本；不存在草稿时缺省或0兼容旧客户端。草稿保存先提交导致旧版完成409；完成先提交则后续保存409，不重新生成草稿。完成沿用原预约/病历/唯一订单/成功审计同事务，删除草稿也在其中。

患者补充与完成共享预约锁：完成先提交则补充409；补充先提交则临床工作区可读最新患者描述。完成不会自动将最新自述写入医生主诉，医生仍明确确认。

更正要求 expectedRevision、规范UUID operationKey、完整 after、非空更正原因（1～2000字符）。锁父预约/病历后检查原医生、COMPLETED、最后版本，再生成 before 和新序号。相同 operationKey/原病历/期望版本/after/原因返回原更正（NO_CHANGE），即便当前最新版本已推进；不同内容或目标复用同键409。重试检查在权限及医院可写核验之后，不能通过幂等查询绕过停用保护。

更正 before 必须是上一 after（首更正为原始病历）；并发同版本更正仅一条成功，另一409，不自行合并。新时间为服务端 Instant，接口 ISO_OFFSET_DATE_TIME +08:00。只读更正历史分页稳定按序号，页长1～5、默认5，授权先于参数解析，总数/列表处于同一数据库视图。用户已批准此处相对初版的修订：完整前后正文使100条即使全ASCII也可达1,200,000字节，超过1MiB；每页最多5条保留正文而不突破既有安全上限，其他列表不变。

这些事务完全不依赖 MinIO。存储停机不阻断补充/草稿/更正。提交后的展示失败不追加业务 FAILURE；客户端通过按ID读取或同幂等键恢复，不能重复生成更正。

## 6. PaaS 契约

所有路径相对于 `/paas/api`。保留原 ClinicalRecordView 字段含义为原始快照，不悄悄将 diagnosis/advice 改成最新文本。新增受限详情返回 original、effective、currentRevision，避免医疗正文进入普通管理或预约列表。

| 方法/路径 | 请求与响应 |
| --- | --- |
| GET/PUT `/patient/appointments/{id}/complaint` | PUT description、expectedRevision；返回 appointmentId、description、updatedAt、revision、writable |
| GET `/doctor/appointments/{id}/clinical-workspace` | 状态、患者描述/修改时间/版本、本人草稿/版本、原始recordId；不会向患者/管理员返回 |
| PUT `/doctor/appointments/{id}/draft` | 四字段、expectedRevision；返回草稿四字段、revision、savedAt |
| POST `/doctor/appointments/{id}/complete` | 原四字段，新增 expectedDraftRevision；返回仍为原 ClinicalRecordView |
| GET `/doctor/records/{id}` | 授权的原始、有效版本及currentRevision，按ID恢复不依赖截断列表 |
| GET/POST `/doctor/records/{id}/corrections` | GET page/pageSize；POST expectedRevision、operationKey、after四字段、reason；返回更正项或分页 |
| GET `/patient/appointments/{id}/record/details` | 本人原始/有效版本/currentRevision，不含草稿 |
| GET `/patient/appointments/{id}/record/corrections` | 本人有序历史分页，包含before/after、原因、原医生标识、时间和序号 |

错误沿用安全 ApiResponse/ApiError 和服务端 requestId：无认证401、无权403、字段400、临床状态冲突409、版本冲突409、幂等键冲突409、审计/基础设施安全500。具体新错误码在实施计划一次冻结；无自由文本异常/SQL/正文进入错误消息，普通JSON响应上限仍1MiB。

## 7. 审计与门户

新增 APPOINTMENT_COMPLAINT_UPDATE(APPOINTMENT)、CONSULTATION_DRAFT_SAVE(CLINICAL_DRAFT)、RECORD_CORRECTION_APPEND(RECORD_CORRECTION)，补齐审计对象枚举及门户筛选/中文标签。完成继续现有 CONSULTATION_COMPLETE，不伪造第二次完成。成功与业务同事务、失败在业务事务结束后独立记录，保持 AuditOperationRunner 单业务EJB约束。

通用审计仅含已验证标量关联、结果、封闭原因和时间；禁止 description/四字段/before/after/reason/正文摘要进入 audit_event、审计页面、容器日志或错误信息。更正内容是授权临床历史，不是管理员审计内容。

Portal 新增独立 ClinicalWorkspace Servlet/工作区及JSP，最小修改现有患者/医生入口，不继续向大 PortalServlet 堆全部处理。会话角色和CSRF检查先于动作；所有写表单POST并PRG，禁JS可保存/完成/更正。正文只在本次request属性中重绘，不放URL、flash、长期session或localStorage；UTF-8及HTML转义，不按HTML渲染医疗文字。

患者显示病情修改入口及只读状态；医生编辑页明确区分患者自述、草稿保存与完成冻结；更正页展示当前有效版本、提交原因及版本冲突提示；双方病历详情分别标识“原始”“当前有效”“更正历史”。陈旧提交时保留本次输入供对照，不自动覆盖新版本；完成按钮明确提示生成订单、更正不改费用。

## 8. 迁移与部署

新 schema 同步完整结构，既有库使用004临床增量迁移，前置是已完成003的精确十表基线。只读校验显式主机/端口/数据库/UUID/全部原表形状/新增目标状态；完整新结构则只读退出，部分/异构拒绝，不自动repair。全库备份成功后才执行DDL，不重跑seed、不改旧正文/订单、不自动部署WAR；失败报告实际结构、保留备份，不DROP或恢复覆盖。

新增预约列会使现有003保护升级器拒绝新结构。部署说明必须明确顺序001/002/003→004，以及004之后用最新临床升级器核验；不为了旧脚本重跑随意放宽基线。若实施需要兼容旧003入口，计划必须列出独立的精确新基线分支测试，不能接受未知混合结构。

先在新专属disposable数据库和双容器验证。旧实例、保护端口3318/8180/8188/10090、教师安装、需求/EAM/备份/历史证据不变。迁移已有运行库另行确认目标和维护窗口，不在附件故障注入窗口同时部署临床改动。MySQL8实测与教师5.6未验分开披露。

## 9. 验收与交付

先失败测试后实现，任务划分为共享契约、保护迁移、数据库业务/锁、API/审计、Portal、独立实机验收。共享schema、ClinicalService、审计枚举和Portal入口指定单一所有者；并行只在无依赖且独占文件的任务间进行。

必须证明：四角色/双患者/跨院/同院非原医生拒绝；临床四状态、账号停用、医院冻结；患者描述时间及版本；私有草稿重开不泄露/不产生订单；旧complete兼容与新版本冲突；完成后草稿消失、唯一病历/订单；每次更正before/after链、幂等重试和变体冲突；原始病历/订单逐字段摘要不变；患者可读历史、分页及按ID恢复。

必须补真实MySQL并发：两标签草稿、完成/保存、完成/病情补充、同版本双更正、医院冻结/写入；成功审计故障回滚及真实WildFly事务边界；新库/旧基线升级/重复只读/部分结构/备份失败拒绝；Portal无JS保存/完成/更正、CSRF、转义、中文、浏览器和键盘。默认可选跳过不算通过，不用H2/mock取代实机门。

阶段报告分列实现、单元、真实数据库/容器、浏览器和未验项。本文批准后才编写实施计划并供审阅，沿用户已选择的多智能体执行方式；不因概念批准跳过具体设计和实施计划。全系统性能/备份恢复/云端/报告/PPT等继续保留。

## 10. 依据和自审

依据：仓库需求分析说明书V1.0 FR-APT-05/FR-CON-05；2026-09-13完整设计4.3与5；2026-09-26课程P0完成设计的旧API兼容；2026-10-04完整范围核对；2026-10-16交接；本次用户明确的推荐概念选择。以较新课程架构和本次状态裁决为准，不移植旧Spring实现作为课程交付证明。

自审：原始/有效/草稿三种含义分离；患者自述不暗中覆盖医生文字；版本与operationKey分别负责并发及重试；更正不改变冻结原始和订单；新迁移顺序与旧升级器精确校验冲突已披露；处方/订单取消/P2未越界。本文无未定字段占位，待审阅的具体规则集中于本文件。
