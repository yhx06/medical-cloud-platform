# 医疗云病情补充、草稿与更正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户已选择多智能体并行；本文待审阅确认，不能先实施后补审批。

**Goal:** 补齐 FR-APT-05/FR-CON-05 及私有接诊草稿，交付可追溯、无痕覆盖不可行的完整诊疗增强链。

**Architecture:** 保留冻结原始 ClinicalRecord，以预约保存患者最新自述、独立 ClinicalDraft 保存私有稿、RecordCorrection 只追加结构化前后版本。父预约锁串行化并发，成功审计与业务同事务；门户独立工作区，不向管理员或患者泄露草稿。

**Tech Stack:** JDK8、Tomcat9 Servlet/JSP、WildFly10 JAX-RS/EJB/JPA/JNDI、MySQL、JUnit5、PowerShell；无新增生产依赖、无新WAR。

**Spec:** [已确认设计](../specs/2026-10-16-medical-cloud-clinical-supplements-design.md)，设计提交 `b0bafec`；实施执行方式沿用户已选择的多智能体。

## Global Constraints

- 患者只在 BOOKED/IN_PROGRESS 可补充，CANCELED/COMPLETED 只读。患者自述与医生 complaint 是两个来源，不自动覆盖。
- 患者描述最长500 Unicode码点；complaint/diagnosis各500、advice/notes各2000，拒绝NUL及非法代理字符，允许正常换行。description可清空为null；诊断/建议在完成、更正时必填。
- 草稿仅启用原医生本人本院可读，保存仅 IN_PROGRESS；无草稿revision0，首次保存1；草稿不生成订单，完成同事务删除草稿。
- 原始 ClinicalRecord 和 PaymentOrder 的任何字段不因草稿/更正而修改；附件继续关联原始record_id；更正不改变诊费、支付或完成时间。
- 父锁序：医院→医生账号→患者账号（患者写适用）→号源→预约→草稿/原病历/更正→成功审计；refresh后重新验证状态/全部冗余归属，不反向加锁。
- 版本为非负整数，陈旧版本409；同版本同内容NO_CHANGE保持时间/版本。更正operationKey为规范UUID，与审计requestId分离。
- 更正before服务端生成；原始版本0，递增sequence；同key/目标/期望版本/after/原因恢复原更正，变体409；仍先重新授权及医院可写。
- 旧complete无草稿时允许缺省complaint/expectedDraftRevision；有草稿必须匹配revision。本次四字段完整提交，不暗中混合缺省草稿值。
- 医院冻结允许授权历史读取、不允许新写；账号停用拒绝；患者tenant_id可空。管理员无临床正文访问权。
- 成功审计与业务同事务、失败在业务事务结束后独立记录；不改AuditOperationRunner单业务EJB约束。正文/更正原因/before/after不入通用审计、错误或日志。
- 普通JSON上限1MiB；完整正文更正历史分页page/pageSize默认1/5、页长1～5、稳定序号（用户已批准尺寸冲突修订，其他列表不变）；先授权再解析分页，总数及行同视图。
- Portal全部写表单POST+CSRF，PRG、禁JS可用、UTF8及转义，正文不入URL/flash/长期session/localStorage。
- 新表InnoDB/utf8mb4/MySQL5.6语法，无级联/JSON/CHECK/8专用；生产JPA validate，不自动DDL。旧数据描述null、时间null、revision0。
- 004前置003十表精确基线，先身份/结构校验及成功全库备份，失败不DROP/覆盖恢复/重跑seed；004后不将旧003拒绝最新结构误判成幂等成功。
- MinIO停机不阻断此切片。只用虚构数据，保护原EAM/需求/备份/历史/脏树及3318/8180/8188/10090、教师安装，不新增PowerDesigner。
- 本计划不实现处方、订单取消、机构开通、AI/评价或真实支付，不删全系统缺口。真实数据库/容器/浏览器未验不能由Mock/H2/跳过冒充。

## Review Focus

1. 用户双标签页及重发：病情/草稿expectedRevision必须防覆盖，更正相同operationKey在后续版本推进后仍恢复原结果（Tasks1/3/4）。
2. 老客户端完成：没有草稿时旧字段兼容；有草稿却无版本拒绝，不默默覆盖或提前产生订单（Tasks3/4/5）。
3. 冻结医院/停用账号持有旧JWT：写入前重新DB授权，原医生之外不可借recordId读取正文（Tasks3/4/6）。
4. 中文/emoji/NUL/未配对代理字符以及HTML正文：长度规则跨端一致，页面不执行输入，也不把正文放入审计或重定向（Tasks1/5/6）。
5. 迁移跨版本/部分DDL：旧003精确校验不得放宽接受未知表；004重复只读、失败备份不DDL、旧病历及订单摘要不变（Tasks2/6）。

## 边界、目录与并行顺序

路径缩写仅用于省重复：C=`course/contract/src/main/java/com/medcloud/course/contract/`；P=`course/paas/src/main/java/com/medcloud/course/paas/`；W=`course/portal/src/main/java/com/medcloud/course/portal/web/`；PT/WT分别为对应模块src/test/java/com/medcloud/course/{paas,portal}/。所有新增文件在下文逐一列出。

Task1冻结DTO/校验/审计枚举后：Task2迁移、Task3后端业务、Task5门户可并行，严格独占文件。Task4路由依赖Task3签名和GREEN，不能写占位服务。Task5按冻结路由实现客户端，真实联调等Task4完成。Task6真实双容器/故障/并发串行，不能和附件Task8在同一环境并发变更。

只有root精确暂存/提交，代理不操作共享Git index；Maven独立源码快照/target，实机SQL/容器由root串行执行。复用现有linked课程工作树，不在顶层旧Spring/Vue开发。每任务报告含实际RED/GREEN、未验/跳过、文件清单，独立规格+质量审查后才解锁依赖。

## 验证命令

M表示 `& 'D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd' -o -f course/pom.xml`，进程级JAVA_HOME为 `D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`，先java/Maven -version证明Java8。测试类筛选同时带 `-Dsurefire.failIfNoSpecifiedTests=false`。RED必须是预期缺失行为/类型或断言，网络/沙箱/外部工具缺失不是功能RED。

### Task 1：契约、内容规则及审计枚举

**Files:** 新建C/{ClinicalContentView,ClinicalContentRequest,PatientComplaintView,SupplementComplaintRequest,ClinicalDraftView,SaveClinicalDraftRequest,ClinicalWorkspaceView,ClinicalRecordDetailsView,AppendCorrectionRequest,RecordCorrectionView,RecordCorrectionPage}.java；修改C/{CompleteConsultationRequest,AuditEventType,AuditObjectType}.java；新建P/consultation/{ClinicalContentRules,ClinicalSupplementException}.java；测试course/contract/src/test/java/com/medcloud/course/contract/ClinicalSupplementContractTest.java、PT/consultation/ClinicalContentRulesTest.java。

**Interfaces:** ClinicalContentView(String complaint,String diagnosis,String advice,String notes)不可变四字段；输入专用ClinicalContentRequest为相同四字段、无参构造+get/set，不能将不可变响应DTO直接用于容器JSON读入。SupplementComplaintRequest为description/String、expectedRevision/Long；SaveClinicalDraftRequest四字段及expectedRevision/Long；AppendCorrectionRequest为expectedRevision/Long、operationKey/String、after/ClinicalContentRequest、reason/String。请求均无参构造+get/set。CompleteConsultationRequest新增expectedDraftRevision/Long，旧构造器不变。

PatientComplaintView(String appointmentId,String description,String updatedAt,long revision,boolean writable)；ClinicalDraftView(ClinicalContentView content,long revision,String savedAt)；ClinicalWorkspaceView(String appointmentId,String status,PatientComplaintView patientComplaint,ClinicalDraftView draft,String recordId,long currentRevision)；ClinicalRecordDetailsView(String recordId,String appointmentId,ClinicalContentView original,ClinicalContentView effective,long currentRevision,String completedAt)；RecordCorrectionView(String id,long sequence,ClinicalContentView before,ClinicalContentView after,String reason,String doctorId,String createdAt)；RecordCorrectionPage(List<RecordCorrectionView> items,long total,int page,int pageSize)，列表防御复制。时间ISO_OFFSET_DATE_TIME+08:00，无草稿draft=null/版本由工作区明确0，不向列表散发正文。

ClinicalContentRules.requireDescription(String)→String、requireDraft(ClinicalContentView)→ClinicalContentView、requireFinal(ClinicalContentView)→ClinicalContentView、requireReason(String)→String、requireRevision(Long)→long、requireOperationKey(String)→String；null四字段草稿允许且规范为空null，final要求诊断/建议。ClinicalSupplementException(int status,String code)固定安全消息，仅以下错误：400 CLINICAL_INPUT_INVALID；409 CLINICAL_STATE_CONFLICT/CLINICAL_REVISION_CONFLICT/CLINICAL_OPERATION_CONFLICT。授权仍AccessDeniedException，不包装异常链。

- [ ] 测试jsonOnlyApprovedFields、legacyCompleteOmitsDraftRevision、unicodeCodePointsBoundary、rejectsNulAndUnpairedSurrogates、emptyDraftButNotEmptyFinal、versionMissingNegativeRejected、uuidKeyCanonical、immutableCorrectionPage；手导断言 `requireDescription(501码点)`→400、500emoji通过，final空诊断400。
- [ ] RED：M -pl contract,paas -am -Dtest=ClinicalSupplementContractTest,ClinicalContentRulesTest -Dsurefire.failIfNoSpecifiedTests=false test；保存实际缺失类型/断言日志。
- [ ] 实现上述契约；审计加三事件APPOINTMENT_COMPLAINT_UPDATE→APPOINTMENT、CONSULTATION_DRAFT_SAVE→CLINICAL_DRAFT、RECORD_CORRECTION_APPEND→RECORD_CORRECTION及两个对象类型，无临床正文审计字段。
- [ ] GREEN：同命令零失败；全构建回归可选skip单列。精确提交 `feat(course): define clinical supplement contracts`。

### Task 2：保护性004迁移

**Files:** 修改course/deploy/schema.sql；新建course/deploy/migrations/004_clinical_supplements.sql、course/deploy/scripts/upgrade-clinical-database.ps1、course/deploy/tests/upgrade-clinical-database.Tests.ps1；本任务不改实体/旧003升级器。

**Interfaces:** 升级器沿既有003显式目标参数：必填string MySqlCli、MySqlDumpCli、HostName、Database、ExpectedServerUuid、BackupPath及int Port（1～65535）；凭据仍进程环境MEDICAL_COURSE_DB_USER/PASSWORD，不加明文密码参数。明确数据库medical_cloud_course、明确UUID，仅专属实例。本计划不授权迁移已有原运行库。

新增预约三列和两表字段严格见Spec4；草稿列id/appointment_id/tenant_id/doctor_id/patient_id/complaint/diagnosis/advice/notes/saved_at/revision；更正列id/record_id/appointment_id/tenant_id/doctor_id/patient_id/sequence_number/before_complaint/before_diagnosis/before_advice/before_notes/after_complaint/after_diagnosis/after_advice/after_notes/reason/created_at/operation_key。草稿四临床列可空，saved_at/revision非空；更正before/after complaint/notes可空、diagnosis/advice非空；sequence_number/reason/created_at/operation_key非空。UUID36、reason2000、DATETIME(6)、BIGINT，唯一及外键不cascade。部署顺序003→004，004完整状态只读退出；旧003之后拒绝新结构写明，不自动放宽。

- [ ] 先写freshSchemaMatchesJpaContract、tenTableBaselineUpgradePreservesOldRows、secondRunReadOnlyWithoutBackup、partialClinicalTablesRefused、wrongUuidOrProtectedPortRefusedBeforeSql、failedBackupPreventsDdl、old003After004HasDocumentedRefusal；断言DDL次数0、旧病历/订单逐行摘要相同，不用源码搜索替代行为。
- [ ] RED：powershell -NoProfile -ExecutionPolicy Bypass -File course/deploy/tests/upgrade-clinical-database.Tests.ps1；缺升级器/保护断言失败，缺MySQL不是RED。
- [ ] 实现只读结构矩阵、完整备份后DDL、部分失败保留结构和备份，无DROP/seed/自动WAR部署；新库schema同步。
- [ ] GREEN：行为测试通过，root独立新MySQL演练zero mandatoryskip；教师5.6未验单列。精确提交 `feat(course): add protected clinical supplements migration`。

### Task 3：持久化、锁和临床业务

**Files:** 修改P/appointment/Appointment.java、P/consultation/ClinicalService.java；新建P/consultation/{ClinicalDraft,RecordCorrection,ClinicalAccessPolicy,ClinicalSupplementService}.java；修改course/paas/src/{main,test}/resources/META-INF/persistence.xml；测试PT/consultation/{ClinicalSupplementDatabaseTest,ClinicalSupplementConcurrencyTest,ClinicalSupplementAuditTest}.java。旧ClinicalRecord/PaymentOrder不增加setter/更正字段。

**Interfaces:** ClinicalSupplementService为@Stateless，提供：PatientComplaintView complaint(CurrentActor,String appointmentId)；PatientComplaintView updateComplaint(CurrentActor,String,SupplementComplaintRequest,AuditContext)；ClinicalWorkspaceView workspace(CurrentActor,String)；ClinicalDraftView saveDraft(CurrentActor,String,SaveClinicalDraftRequest,AuditContext)；ClinicalRecordDetailsView doctorDetails(CurrentActor,String recordId)；ClinicalRecordDetailsView patientDetails(CurrentActor,String appointmentId)；RecordCorrectionPage correctionsForDoctor(CurrentActor,String recordId,String page,String pageSize)；RecordCorrectionPage correctionsForPatient(CurrentActor,String appointmentId,String page,String pageSize)；RecordCorrectionView appendCorrection(CurrentActor,String recordId,AppendCorrectionRequest,AuditContext)。默认Instant.now，包级带Instant测试重载不改变HTTP可指定时间。

ClinicalService新增complete(CurrentActor,String appointmentId,String complaint,String diagnosis,String advice,String notes,Instant now,AuditContext context,Long expectedDraftRevision)→ClinicalRecord；旧重载委派并传null。ClinicalAccessPolicy仅集中现有临床父锁及新患者写/历史读授权，ClinicalService旧语义保留，避免复制锁逻辑或借用附件私有工具。首次草稿在预约锁下插入，version为显式业务revision（不混成Hibernate内部version）；更正期望版本可由序号-1恢复，与operationKey请求指纹一起核验。

- [ ] 测试 patientDescriptionTimestampAndNoChange、completedCanceledRejectPatientUpdate、draftPrivateAndNoOrder、saveThenReopenDraft、legacyCompleteWithoutDraftStillWorks、draftRequiresMatchingVersionOnComplete、completeDeletesDraftAtomically、correctionBeforeAfterChain、sameKeyRecoversAfterLaterCorrection、keyVariantConflict、originalRecordOrderUnchanged、disabledAccountAndFrozenHospital、sameHospitalWrongDoctorDenied。
- [ ] 写并发/审计：twoTabsOneRevisionWins、completeVsDraftNoResurrection、completeVsDescriptionStateRechecked、twoCorrectionsOneSequence、auditFailureRollsBackComplaintDraftAndCorrection；字面断言 `order count before draft == after draft == 0`、首更正sequence1/before原始、重复key总数1、审计失败后业务摘要不变。
- [ ] RED：M -pl paas -am -Dtest=ClinicalSupplementDatabaseTest,ClinicalSupplementConcurrencyTest,ClinicalSupplementAuditTest -Dsurefire.failIfNoSpecifiedTests=false test；保留实际断言。
- [ ] 实现实体/显式持久化映射/共享父锁、版本和幂等、成功审计；失败审计留资源runner，不在业务事务中吞异常。完成只采当前请求四字段并原子删除草稿。
- [ ] GREEN：同命令通过及原ClinicalPaymentFlowTest/AuditClinicalFlowTest/AttachmentDatabaseTest回归；此处H2不宣称真实MySQL/JTA。精确提交 `feat(course): persist clinical drafts and corrections`。

### Task 4：PaaS路由与错误审计

**Files:** 新建P/api/ClinicalSupplementResource.java；修改P/api/{ClinicalResource,ApiFailureMapper}.java；测试PT/{ClinicalSupplementDispatchTest,ClinicalSupplementResourceTest}.java。Task3拥有ClinicalService，Task4不得改其实现。实施定位勘误：旧complete实际位于ClinicalResource，原AppointmentResource为文件名笔误；替换文件所有权，不增加路由/任务范围。

**Interfaces:** 消费Task3全部签名；路由与DTO恰为Spec6表。现有complete调用新重载传expectedDraftRevision；其他旧响应不变。ClinicalSupplementException映射固定status/code/requestId，拒绝自由文本。查询/错误信息不得含未授权正文；成功写入口@AuditTracked+AuditOperationRunner单业务EJB，不另调用多个业务事务。

- [ ] 测试 anonymousBeforeInvalidPage401、foreignParentDeniedBeforePagination、administratorCannotReadDraftOrCorrections、crossPatientAndUnassignedDoctor403、requestCannotOverrideActorTenantTime、newErrorsHaveSafeRequestId、oldCompleteNoDraftCompatibility、withDraftMissingRevision409、repeatedCorrectionOneRowAndNoChangeAudit、fiveFullLengthUnicodeCorrectionsFitOneMiB、authorizedPageSizeSixRejected400；实际RESTEasy派发，不只调用方法。容量断言在真实JSON响应UTF8字节上执行，不仅算Java字符数。
- [ ] RED：M -pl paas -am -Dtest=ClinicalSupplementDispatchTest,ClinicalSupplementResourceTest -Dsurefire.failIfNoSpecifiedTests=false test；路由/行为失败而非占位service。
- [ ] 实现独立资源及旧complete最小接线、分页先授权、DTO请求验证及安全失败审计。after从客户端读入须支持容器JSON构造，不能测试专用ObjectMapper替代真实JSON门。
- [ ] GREEN：同命令及ApiFailureTest/AuditRequestIdsTest/AttachmentDispatchTest回归。精确提交 `feat(course): expose clinical supplement endpoints`。

### Task 5：患者/医生门户

**Files:** 新建W/{ClinicalSupplementServlet,ClinicalSupplementWorkspace}.java；修改course/portal/src/main/java/com/medcloud/course/portal/factory/CloudServiceFactory.java；修改course/portal/src/main/webapp/WEB-INF/views/{patient,doctor}.jsp、audit.jspf；新建同目录{patient-complaint,clinical-workspace,clinical-record-details,clinical-correction}.jsp；测试WT/{ClinicalSupplementPortalTest,ClinicalSupplementClientTest,ClinicalSupplementJspTest}.java。现有PortalServlet不在本任务修改范围，新增Servlet以注解映射独立URL，不复制原登录/会话机制。

**Interfaces:** 复用PaasClient既有put(String path,String token,Object body)→JsonNode，与既有get/post同1MiB上限和安全错误；不重复添加或改写通用传输。ClinicalSupplementWorkspace.load(HttpServletRequest,String token,Role,String targetId)→void，动作在新Servlet处理。GET /patient/complaint、/doctor/clinical、/doctor/record-details、/patient/record-details、/doctor/correction；POST /patient/complaint/save、/doctor/clinical/save、/doctor/clinical/complete、/doctor/correction/append。后端路径由固定代码选择，不接受浏览器目标URL/token/tenant。新增表单显示expectedRevision和operationKey（重试保留键，不换键重发）。

- [ ] 测试 roleAndCsrfBeforeApiWrite、noJsDraftSaveAndCompletePrg、textNotInRedirectOrSession、patientDraftNeverRendered、patientCopyExplicitNotAutomatic、unicodeAndHtmlEscaped、versionConflictKeepsRequestInputNotFlash、correctionRetryKeepsOperationKey、originalEffectiveHistoryDistinct、frozenHospitalReadOnly、auditLabelsCoverThreeNewEvents。
- [ ] RED：M -pl portal -am -Dtest=ClinicalSupplementPortalTest,ClinicalSupplementClientTest,ClinicalSupplementJspTest -Dsurefire.failIfNoSpecifiedTests=false test；缺页面/行为断言失败。
- [ ] 实现受限详情与表单、成功PRG、失败仅当次request重绘、原始/有效/历史分别标识；病情复制只在医生明确动作时进行，禁JS仍能填主诉。患者病情与医生草稿入口分离，管理员仅审计新标签。
- [ ] GREEN：同命令及PortalFlowTest/AuditPortalTest回归；`& 'D:/nodejs/node.exe' --test course/portal/src/test/js/portal.test.cjs`（已有交互回归不作新页面浏览器证明）。精确提交 `feat(course): add clinical supplement workspaces`。

### Task 6：真实迁移、并发、双容器与阶段交付

**Files:** 新建course/deploy/scripts/verify-clinical-supplements.ps1、course/deploy/tests/clinical-supplements-acceptance.Tests.ps1、PT/consultation/MySqlClinicalSupplementTest.java、docs/course-labs/2026-10-16-clinical-supplements-acceptance.md；修改course/deploy/README.md及docs/course-labs/2026-10-04-full-scope-inventory.md（只更新有证据条目，不改历史记录成新验收）。

**Interfaces:** -FixturePath/-EvidenceRoot显式owned新环境，复用只读归属工具；既有未停附件测试环境不部署临床WAR。每门摘要required/executed/passed/failed/skipped/unverified，任何必需missing/skip非零；不从历史结果导入通过。真实MySQL测试仅唯一disposableDB/JPAvalidate，独立只读lockobserver，无root测试账号。

- [ ] RED：先写 missingRequiredGateCannotPass、foreignFixtureBeforeEveryWrite、clinicalTextNotInEvidenceOrAudit、operationKeyRetryAfterLostResponse；行为运行验证，不用脚本文本搜索。
- [ ] 从空环境/schema和十表旧基线两条路径迁移004，读取旧病历/订单摘要；真实MySQL行锁并发观察，不以sleep推测锁等待；双容器验证新JSON/旧complete、成功审计回滚/独立失败审计、草稿不可泄露及MinIO停机诊疗功能仍可用。
- [ ] 真实Chrome/Edge普通及禁JS观察患者补充、医生草稿/完成/更正和患者历史、401/403/409/冻结、中文/转义/键盘。不能连接的浏览器逐项未验，不叫通过。
- [ ] GREEN：M clean verify（披露可选skip）；新gate mandatory零failure/skip/unverified，并完成独立全切片审查及修复。报告分层，不代表整个系统完成；仅精确停止owned实例/保留数据。
- [ ] 精确提交 `test(course): verify clinical supplement lifecycle`，停下来汇报此大阶段。推送/合并前另外核整分支审查及目标树保护，不为赶进度跳过门。

## 主代理计划自审与审批状态

Spec1～3→Tasks1/3/5；4→Tasks2/3；5→Tasks3/4/6；6→Tasks1/4/5；7→Tasks1/3/4/5/6；8→Tasks2/6；9→各任务TDD/审查/Task6。五项ReviewFocus均有具名测试，原始/有效字段无混用，operationKey/expectedRevision/审计requestId独立；共享文件已明确单一所有者。

用户已确认本计划并要求开始并行实施；更正历史每页最多5条的后续修订亦已确认，不再重复询问设计、计划或执行方式。按上述依赖推进。附件验收可在此期间继续，但任何临床实机部署等附件故障窗口退出后串行执行。
