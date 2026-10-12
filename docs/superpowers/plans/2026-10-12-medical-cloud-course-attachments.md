# 医疗云课程版附件服务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户已选择多智能体并行；计划审阅确认前不执行产品编码。

**Goal:** 实现患者预约资料与医生已完成病历附件的私有上传、分页、恢复、删除、五分钟下载授权及审计，交付隔离环境的可复现演示。

**Architecture:** 保留老师要求的双 WAR 课程架构。PaaS 以持久上传意图、短 EJB 事务、事务外 MinIO 调用和后台补偿处理数据库/对象存储一致性；Portal 独立 Servlet 接收有界 multipart 并使用会话令牌转发。存储故障仅降级附件，不阻断核心诊疗。

**Tech Stack:** JDK 8、Tomcat 9 Servlet/JSP、WildFly 10 Java EE 7/JAX-RS/EJB、JPA/JNDI、MySQL、私有 MinIO、JUnit 5、PowerShell；MinIO SDK 8.5.17 与 PDFBox 2.0.34 是 Task 1 的精确候选，未作安全/兼容通过声明。

**Spec:** [已确认附件设计](../specs/2026-10-12-medical-cloud-course-attachments-design.md)，实施基线 `codex/course-environment` / `975e8f31d16238425f9ce1b0bc1372bf4d951691`。

## Global Constraints

- 默认最大文件长度为 `10 * 1024 * 1024` 字节；门户文案为“10 MB（10,485,760 字节）”；Portal/PaaS 配置一致。
- JPG/JPEG、PNG、PDF；文件名最长 255 UTF-8 字节；随机 ASCII object_key 最长 128 字符；真实格式解析及 SHA-256 校验。
- 图像默认最多 20,000,000 像素；PDF 至少一页、默认最多 200 页，拒绝加密、嵌入文件、JavaScript、启动及自动打开动作，包括嵌套动作。
- 每容器默认四个并行文件处理请求、每上传者最多两个未结束上传；连接超时 3 秒，存储调用总时限 90 秒，内容接收总时限 120 秒。
- RESERVED 有效期 15 分钟，UPLOADING 租约 10 分钟；同一行 id 即 uploadId/fileId；唯一 `(uploader_id, upload_key)`，attempt token fencing。
- 后台最多每分钟一次、每批最多 100 条；失败退避 1/2/4/8/16/30 分钟；ABORTED/DELETED 墓碑持续每 30 分钟核对，未知对象不擅删。
- 下载签名 300 秒，expiresAt 从签名生成时刻计算；签发成功事务提交后才返回；链接是持有者凭证，不能承诺立即撤销。
- 患者 BOOKED/IN_PROGRESS 可上传，COMPLETED/CANCELED 禁止；删除仅原上传患者、医院启用、BOOKED 且 started_at 为空；医生只追加本人本院已完成病历，不覆盖或重新生成订单。
- 医院停用禁止新写、允许启用账号授权历史读取；管理员不得访问附件内容/文件名/对象键/签名。患者账号 tenant_id 可空，不拿它替代预约归属。
- 锁序：医院 → 接诊医生账号 → 上传者账号（同一账号只锁一次）→ 号源 → 预约 → 附件；存储等待无 JTA，不改 AuditOperationRunner 单业务 EJB 约束。
- 成功审计与发布/逻辑删除/签发同事务，失败独立事务。代码使用现有 AuditResult.FAILED/SUCCESS/NO_CHANGE，不新增自由文本原因。
- 新表采用 InnoDB/utf8mb4、MySQL 5.6 语法；无级联删除、无旧行改写；迁移先核完整九表/目标 UUID/端口/库，再备份。
- 普通 JSON 响应仍限制 1 MiB；无 RESTEasy multipart provider、无 Spring 产品依赖、无存储凭据进入 Portal。
- 签名页面/响应 no-store、no-referrer；URL 不入 Location/session flash/localStorage/日志；上传/删除支持禁 JavaScript。
- 只用虚构/脱敏数据。保护 EAM、原需求、脏工作树、备份及旧证据；不碰 task8-runtime-20260928 和 3318/8180/8188/10090；不制作 PowerDesigner，不启动 70 台服务器。
- 本计划只在新 disposable DB、独立双容器/MinIO 验收；已有运行库迁移/部署另行确认目标与维护窗口。跳过不算通过，MySQL 8 实测不能称教师 MySQL 5.6 已验。

## Review Focus

1. 存储等待期间令牌过期：发布/签发前重新认证，不仅检查账号启用（Task 6）。
2. 畸形文件名、Unicode 字节长度、循环/过深 PDF 动作：拒绝或安全展示，不能绕过限制/耗尽线程（Task 2）。
3. 上传已提交但响应丢失、查库暂不可用：不能猜测失败删除 AVAILABLE 对象（Task 6）。
4. multipart 重复字段、伪造目标、无 Content-Length 慢速输入：拒绝覆盖/有界超时，签名链接不入不安全来源（Task 7）。
5. 残缺迁移与错误实例归属：只读拒绝，不恢复覆盖、不关闭他人进程（Task 3/9）。

## 文件职责与单一写入者

以下路径均相对实施工作树。Java 新文件逐一列于任务；包基址定义为 C=`course/contract/src/main/java/com/medcloud/course/contract/`，A=`course/paas/src/main/java/com/medcloud/course/paas/attachment/`，W=`course/portal/src/main/java/com/medcloud/course/portal/web/`；CT/PT/WT 分别为上述模块的 `src/test/java/com/medcloud/course/{contract,paas,portal}/`。这些缩写只省重复前缀，不改变完整路径。

| 所有者 | 独占文件 | 职责 |
| --- | --- | --- |
| Task 1 集成负责人 | C 新 DTO/审计枚举、course/paas/pom.xml、新 AttachmentException/AttachmentLimits/内部值对象与接口 | 冻结跨代理契约、依赖兼容门；以后只有其负责人改共享接口/POM |
| Task 2 存储代理 | A 的验证器/MinIO 适配/存储配置及自己的测试 | 真实解析、资源上限、私有存储预检和有界调用 |
| Task 3 迁移代理 | schema.sql、003 SQL、新保护升级器及迁移测试 | 表结构、保护备份与重入；不编辑实体 |
| Task 4 数据库代理 | A 实体/访问策略/数据库 EJB、主/测试 persistence.xml、自己的数据库测试 | 权限、锁、状态、分页及成功审计 |
| Task 5 API 代理 | AttachmentResource、ApiFailureMapper、自己的路由测试 | 八路由、错误映射、requestId |
| Task 6 集成负责人 | A 编排器/回收器/失败记录及自己的测试 | 调用各代理契约，不改其实现；恢复与独立失败审计 |
| Task 7 Portal 代理 | W 附件文件、PaasClient、CloudServiceFactory、patient/doctor/hospital/platform JSP、自己的测试 | multipart、详情、授权页面、管理审计标签；不往 PortalServlet 堆上传处理 |
| Task 8 验收负责人 | 新验证入口/端到端测试/验收文档与 deploy README | 双容器、私有桶、故障演示与运行证据 |
| Task 9 验收负责人 | 新夹具归属工具/测试 | 精确实例生命周期，禁止共用原运行库 |

## 并行执行顺序与汇报点

批准计划后读 worktrees/TDD/子代理实施技能并检查现有工作树，优先复用合适隔离树；子任务采用独立提交树或严格独占文件。只有集成负责人动主 Git index，代理不得在同一个 index 并发暂存；测试各用独立临时目录/数据库/bucket，实体和 SQL 先按设计第 6 节逐字段确认。

前置波：Task 9 先完成隔离归属工具与新实例，供 Task 1 实机兼容探针使用。第一波：Task 1 完成并审查后，Task 2、Task 3、Task 7 并行；负责人冻结 Task 4 接口并接 Task 4（最多三子代理，空槽再派）。第二波：Task 4 完成后 Task 5 与 Task 6 可并行，Task 5 的实现联接/最终GREEN等待 Task 6 方法可用，不能提交占位编排。第三波：Task 8 集成及实机验收串行，以免端口/进程/故障注入相互干扰。

每任务先规格审查、再代码质量审查，问题交回原实现代理；不让依赖任务越过未通过的接口门。第一波基础可运行、后端链路贯通、完整附件验收是三个大阶段汇报点，分别列已实现/测试证据/未验项。附件完成后暂停汇报，再按完整范围清单推进下一子系统，不能宣布全系统完成。

## 统一红绿命令

执行前重新确认工具存在；PowerShell 设置任务级 JAVA_HOME/PATH 指向 Java 8，并运行 java -version、Maven -version 证明 Maven 使用 Java 8。当前候选路径：JDK `D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`；Maven `D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`；Node `D:/nodejs/node.exe`。

下面 M 表示 PowerShell `& 'D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd' -f course/pom.xml`。每个任务把命令中 M 替换为此完整调用。RED 必须先执行、保留实际断言失败或缺失类型的编译失败；依赖/网络/环境错误不是预期红灯。GREEN 必须退出 0、该任务必需测试 failed=0/error=0/skipped=0；默认构建的可选 MySQL 跳过另列，不算数据库门通过。

每任务提交仅显式列出其 Files；不使用 git add . 或 glob。在独立树以 `git add -- <该任务实际完整路径>`、`git diff --cached --check`、核对 staged 文件后执行所列提交消息；保留新鲜 RED/GREEN 证据。

### Task 1：共享契约及 Java 8/WildFly 依赖门

**Files:** 新建 C/{AttachmentSourceType,AttachmentState,AttachmentView,AttachmentPage,PrepareUploadRequest,UploadOperationView,AttachmentDeleteView,DownloadAuthorizationView}.java；修改 C/AuditEventType.java、AuditObjectType.java；修改 course/paas/pom.xml。新建 A/{AttachmentException,AttachmentLimits,AttachmentDescriptors,ValidatedAttachment,UploadAttempt,AttachmentSnapshot,CleanupCandidate,StoreObjectInfo,StoreDownload,PrivateObjectStore}.java；测试 CT/AttachmentContractTest.java、PT/AttachmentDependencyTest.java、PT/probe/SdkProbeServlet.java（仅测试探针，不进入产品WAR）；记录 docs/course-labs/2026-10-12-attachments-dependencies.md。

**Interfaces — Consumes:** 既有 ApiResponse/审计枚举与 Task 9 fixture；**Produces:** DTO Java 8 构造器/getters。AttachmentView(String fileId, AttachmentSourceType sourceType, String fileName, String contentType, long sizeBytes, String createdAt)；AttachmentPage(List<AttachmentView> items,long total,int page,int pageSize)；PrepareUploadRequest 的可反序列化字段为 String uploadKey/fileName/contentType/sha256、long sizeBytes；UploadOperationView(String uploadId,AttachmentState state,AttachmentView file)；AttachmentDeleteView(String fileId,AttachmentState state)；DownloadAuthorizationView(String fileId,String url,String expiresAt)。时间为 ISO_OFFSET_DATE_TIME +08:00；列表防御复制。

内部非 JSON 值对象构造器/getters：ValidatedAttachment(Path path,String fileName,String contentType,long sizeBytes,String sha256)；UploadAttempt(String uploadId,AttachmentState state,String attemptToken,String objectKey,String fileName,String contentType,long sizeBytes,String sha256,Instant leaseUntil,AttachmentView availableFile)；AttachmentSnapshot(String fileId,String objectKey,String fileName,String contentType,long version)；CleanupCandidate(String fileId,String objectKey,AttachmentState state,long version)；StoreObjectInfo(long sizeBytes,String sha256)；StoreDownload(String url,Instant expiresAt)。ValidatedAttachment 仅验证器包内构造；不得由 HTTP 请求直接创建。

PrivateObjectStore：void put(String objectKey,ValidatedAttachment content)、StoreObjectInfo head(String objectKey)（不存在返回 null）、StoreDownload signGet(AttachmentSnapshot file,Instant issuedAt,int ttlSeconds)、void delete(String objectKey)、List<String> listKeys(String prefix,String afterKey,int limit)。不泄露 SDK 类型，列举分页/前缀受控。

AttachmentException(int status,String code) 提供 getStatus()/getCode()，safe message 使用固定白名单；AttachmentLimits 的字段为 long maxBytes/maxPixels、int maxPdfPages/containerConcurrency/uploaderPendingLimit/connectTimeoutSeconds/storeDeadlineSeconds/receiveDeadlineSeconds，默认值来自 Global Constraints。

AttachmentDescriptors.requireValid(PrepareUploadRequest request,AttachmentLimits limits) → PrepareUploadRequest（校验UUID、1..maxBytes、64位十六进制摘要、后缀/MIME对应、文件名去路径且UTF8字节限额，返回规范描述）；准备和真实解析都调用同一规则，但不拿它代替内容验证。AttachmentSourceType 恰好两值，AttachmentState 恰好设计的六值。

- [ ] 编写 metadataSerializationContainsOnlyApprovedFields（JSON 字段恰好六个展示字段）、operationIdEqualsPublishedFileId、fileEventsUseFileMetadataObjectType（恰好新增三事件，不改旧事件）、internalFieldsNeverAppearInPublicViews；依赖测试 sdkClassLoadsOnJava8 及 warDoesNotBundleJavaEeOrResteasy。
- [ ] RED：M -pl contract,paas -am -Dtest=AttachmentContractTest,AttachmentDependencyTest -Dsurefire.failIfNoSpecifiedTests=false test；预期缺失新类型/明确字段断言失败。
- [ ] 实现 DTO/接口/限制/异常，审计新增 FILE_UPLOAD/FILE_DELETE/FILE_DOWNLOAD_AUTHORIZE → FILE_METADATA；将 MinIO 8.5.17、PDFBox 2.0.34 作为精确依赖候选核对上游维护/已知漏洞/传递依赖与字节码 ≤52。锁安全兼容版本后记录决定；如需换同系列兼容补丁，统一更新本计划/证据，禁止悄改 Java/容器架构。
- [ ] GREEN：上述命令通过；M -pl paas -am dependency:tree、M -pl paas -am package；AttachmentDependencyTest 在 COURSE_ATTACHMENTS_FIXTURE 指向 Task 9 清单时，构建仅测试用探针WAR、部署真实Java8/WildFly10并断言SDK实例化/既有JSON序列化正常，记录WAR jar清单；未提供夹具应明确门未执行而非兼容通过。此兼容门未通过不得派存储实现或宣称版本已锁安全。
- [ ] 提交：feat(course): define attachment contracts and compatible dependencies（只提交本任务文件）。

### Task 2：真实验证、私有存储及附件配置降级

**Files:** 新建 A/{AttachmentValidator,MinioPrivateObjectStore,AttachmentConfiguration,AttachmentProcessingGate}.java；测试 PT/{AttachmentValidatorTest,PrivateObjectStoreTest,AttachmentConfigurationTest}.java。Task 1 owner 负责必要 POM 修改，其他代理不写 POM。

**Interfaces:** Consumes Task 1 值对象/存储接口/limits。Produces AttachmentValidator.validate(InputStream input,Long declaredLength,PrepareUploadRequest descriptor,AttachmentLimits limits,Path tempDirectory) → ValidatedAttachment（调用者负责 finally 清理）；AttachmentProcessingGate.tryAcquire() → AutoCloseable（满时抛 429）；AttachmentConfiguration.fromEnvironment(Map<String,String> env) → AttachmentConfiguration，isAvailable() → boolean、getLimits() → AttachmentLimits、createStore() → PrivateObjectStore（禁用/坏配置抛安全 503，初始化核心 WAR 不抛致命异常）。

配置名冻结为 COURSE_ATTACHMENTS_ENABLED、COURSE_ATTACHMENTS_MAX_BYTES、COURSE_ATTACHMENTS_MAX_PIXELS、COURSE_ATTACHMENTS_MAX_PDF_PAGES、COURSE_ATTACHMENTS_CONCURRENCY、COURSE_ATTACHMENTS_UPLOADER_PENDING_LIMIT、COURSE_ATTACHMENTS_TEMP_DIR；PaaS另读 COURSE_S3_INTERNAL_ENDPOINT/PUBLIC_ENDPOINT/REGION/BUCKET/ACCESS_KEY/SECRET_KEY/PREFIX（PREFIX默认course-attachments/），Portal另读 COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS（逗号分隔scheme/host/port来源白名单）。秒限制与 Global Constraints 一致，内部测试可注入较短期限；配置只认正整数，不接受浏览器覆盖。

- [ ] 写 exactTenMiBAcceptedAndOneByteMoreRejected、mismatchedDigestAndLengthRejected、spoofedMimeAndCorruptFileRejected、oversizedImageDimensionsRejectedBeforeDecode、pdfEncryptedEmbeddedJavascriptAndNestedActionsRejected、cyclicAndDeepPdfActionsBounded、unicodeNameBytesAndCrLfRejected；assert 10,485,760 上限、20,000,000 像素、200 页，解析失败不 PUT；写 fourConcurrentProcessorsAndFifth429、dripFeedDeadlineWithoutContentLength、badStorageConfigurationDoesNotDisableCore。
- [ ] RED：M -pl paas -am -Dtest=AttachmentValidatorTest,PrivateObjectStoreTest,AttachmentConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test；预期新实现缺失/上述限制断言失败。
- [ ] 实现先尺寸/签名再解码、PDFBox2真实解析及去重有界动作遍历、SHA256/长度校验、隔离临时目录。MinIO 适配区分内部/外部端点，在受控外部端点签名，不事后换 hostname；总调用时限覆盖重试并发，不以 read timeout 代替总时限。
- [ ] 写 unsignedGetAndListDenied、signedGetMatchesDigestAndExpiresAt300Seconds、tamperedSignatureRejected、versionedOrLockedBucketRejected、rootCredentialsNotUsedByApplication、storeTotalDeadlineBoundsRetries；纯适配单元与独立真实 MinIO 两套证据分列，未知异常内容不入日志。
- [ ] GREEN：同测试命令通过；在 Task 9 的专用实例验证以上真实存储断言（到期需实际观察，不只读签名参数，COURSE_ATTACHMENTS_FIXTURE指向清单）；附件配置失败不影响核心初始化，完整核心链故障演示由Task8执行。
- [ ] 提交：feat(course): validate attachments and use private object storage。

### Task 3：003 表结构与保护性迁移

**Files:** 新建 course/deploy/migrations/003_attachments.sql、course/deploy/scripts/upgrade-attachments-database.ps1、course/deploy/tests/upgrade-attachments-database.Tests.ps1；修改 course/deploy/schema.sql。仅这些文件归迁移代理。

**Interfaces:** Consumes spec 第6节全部字段和 Task 1 枚举。Produces file_metadata 表；升级器参数与既有 audit 升级器一致：-MySqlCli -MySqlDumpCli -HostName -Port -Database -ExpectedServerUuid -BackupPath，凭据来自 MEDICAL_COURSE_DB_USER/PASSWORD。Database 必须精确 medical_cloud_course；测试先由 Task 9 创建独立实例。

- [ ] 写 freshSchemaAnd003HaveIdenticalShape、nineTableRowsRemainUnchanged、secondRunIsReadOnlyWithoutBackup、wrongUuidPortDatabaseAndBaselineRejected、partialNewTableRejected、backupFailureAndExistingPathPreventDdl、partialDdlReportsActualShapeWithoutRetry；PowerShell 断言拒绝路径上备份/DDL 调用次数为0，重复执行不更新任一旧行。
- [ ] RED：powershell -NoProfile -File course/deploy/tests/upgrade-attachments-database.Tests.ps1；预期缺失升级器/新表断言失败，不能把无 MySQL 工具当 RED。
- [ ] 按 spec 字段顺序实现 SQL（ID varchar36，名 varchar255，key/upload UUID 用 ASCII 对应排序以核算字节）；InnoDB/utf8mb4、指定索引/外键，MySQL5.6 语法。保护器核完整九表的列/排序规则/默认/索引/外键及 UUID；正确新表只读退出；备份新绝对路径成功且非空后 DDL；异构/残缺拒绝、部分DDL报告事实，无 DROP/自动恢复/seed。
- [ ] GREEN：同命令真实 disposable MySQL 全部断言通过；核外键/索引/旧九表摘要；Task4实体登记后由Task8补JPA validate。测试MySQL实际版本写入报告，未测5.6不宣称已测。
- [ ] 提交：feat(course): add guarded attachment database migration。

### Task 4：数据库状态机、授权和事务审计

**Files:** 新建 A/{FileMetadata,AttachmentAccessPolicy,AttachmentDatabaseService}.java；修改 course/paas/src/{main,test}/resources/META-INF/persistence.xml；测试 PT/{AttachmentPersistenceTest,AttachmentAccessPolicyTest,AttachmentDatabaseTest,MySqlAttachmentConcurrencyTest,MySqlAttachmentTransactionTest}.java。

**Interfaces — Consumes:** Task 1 的全部标量快照/DTO/limits、既有 CurrentActor/AuditContext/TenantAccessPolicy/AuditJournal。**Produces:** @Stateless AttachmentDatabaseService 短事务方法：
preparePatient(CurrentActor,String appointmentId,PrepareUploadRequest,Instant) → UploadOperationView；
prepareDoctor(CurrentActor,String recordId,PrepareUploadRequest,Instant) → UploadOperationView；
claim(CurrentActor,String uploadId,Instant,AuditContext) → UploadAttempt；
publish(CurrentActor,String uploadId,String attemptToken,ValidatedAttachment,Instant,AuditContext) → AttachmentView；
operation(CurrentActor,String uploadId) → UploadOperationView；
listForPatient/listForDoctor(CurrentActor,String appointmentId,String rawPage,String rawPageSize) → AttachmentPage；
logicalDelete(CurrentActor,String fileId,Instant,AuditContext) → AttachmentDeleteView；
downloadCandidate(CurrentActor,String fileId) → AttachmentSnapshot；
commitDownloadAuthorization(CurrentActor,String fileId,long expectedVersion,Instant expiresAt,Instant now,AuditContext) → void；
resolveUpload(CurrentActor,String uploadId,String attemptToken) → UploadOperationView；
abortAttempt(String uploadId,String attemptToken,Instant) → boolean；
claimCleanupBatch(Instant,int limit) → List<CleanupCandidate>；
recordCleanup(String fileId,long expectedVersion,boolean confirmedAbsent,Instant) → void。

- [ ] 写 sameKeySameDescriptorReturnsSameIntent（行数1，任何目标/描述差异409）、patientInProgressCanPrepareAndPublish、doctorAppendRequiresCompletedOwnedRecord、appendDoesNotChangeRecordOrderOrCompletionTime、disabledHospitalAllowsHistoryButRejectsWrites、adminsAndWrongOwnerSeeNoMetadata、staleAttemptCannotPublishAfterAbort、publishAuditFailureRollsBackAvailable、repeatedDeleteReturnsOriginalStateAndNoChange、authorizationBeforeBadPagination。
- [ ] RED：M -pl paas -am -Dtest=AttachmentPersistenceTest,AttachmentAccessPolicyTest,AttachmentDatabaseTest -Dsurefire.failIfNoSpecifiedTests=false test；预期缺失 EJB/授权及状态断言失败。
- [ ] 实现实体登记、显式 @Version、标量快照、每次重读授权与共同锁序、幂等/配额/租约、稳定 created_at/id 真分页。prepare调用Task1描述校验；总数/行处于同一REPEATABLE_READ视图（部署附件使用的数据源需明确核隔离级别，Task8配置/证明；不能以REQUIRED注解替代隔离）。普通查询只返回 AVAILABLE。失败不做存储调用；成功审计与状态同事务，准备不是上传成功。
- [ ] 写真实 MySQL 测试 concurrentReservationsRespectUploaderQuota（同上传者跨预约最多2）、countAndRowsShareRepeatableReadSnapshot、completionWinsAgainstPatientPublish/publishWinsAgainstCompletion、startWinsAgainstDelete/deleteWinsAgainstStart、hospitalFreezeWinsAgainstPublish、downloadReadLockSerializesWithLogicalDelete；真实代理事务中 injectAuditFailureRollsBackPublicationAndAuthorization。并发两种提交顺序都要锁/屏障证据，不用 sleep 猜竞争成功。
- [ ] GREEN：上述单元命令及 M -pl paas -am -Pmysql-integration -Dtest=MySqlAttachmentConcurrencyTest,MySqlAttachmentTransactionTest -Dsurefire.failIfNoSpecifiedTests=false test（COURSE_MYSQL_TEST_URL 等只指 Task 9 新唯一库，测试必须实际运行）；WildFly JTA 的断言留给 Task 8，不把 RESOURCE_LOCAL 当容器证明。
- [ ] 提交：feat(course): persist authorized attachment lifecycle。

### Task 5：八路由及错误边界

**Files:** 新建 course/paas/src/main/java/com/medcloud/course/paas/api/AttachmentResource.java；修改同目录 ApiFailureMapper.java；测试 PT/{AttachmentResourceTest,AttachmentDispatchTest}.java、修改 PT/ApiFailureTest.java。AttachmentException 如需改动只由 Task 1 owner 进行。

**Interfaces:** Consumes Task 4 DB 与 Task 6 coordinator 方法；Produces spec 第9节八路由，方法返回 javax.ws.rs.core.Response，成功 entity=ApiResponse<T>、失败沿用 ApiError。路径/HTTP 方法不新增隐含管理员下载入口；PUT content 为方法级 application/octet-stream，准备为 JSON，分页传原始字符串。

- [ ] 写 anonymousInvalidPaginationReturns401、wrongOwnerInvalidPaginationReturns403、rawBinaryRouteAcceptsOctetStream、attachmentErrorsKeepStableCodesReasonsAndRequestId、operationRecoveryDoesNotDependOnListPage、downloadResponseIsNoStoreAndNotInList；参数化完整角色/双医院/双患者/同院非分配医生/替换四类ID矩阵。
- [ ] RED：M -pl paas -am -Dtest=AttachmentResourceTest,AttachmentDispatchTest,ApiFailureTest -Dsurefire.failIfNoSpecifiedTests=false test；预期新路由/413/429/503映射断言失败。
- [ ] 实现认证→父资源授权→分页解析；安全错误400 FILE_INVALID/UPLOAD_DESCRIPTOR_INVALID、413 FILE_TOO_LARGE、415 FILE_TYPE_UNSUPPORTED、409三种冲突、429 FILE_LIMIT_REACHED、503 OBJECT_STORE_UNAVAILABLE，按 spec 第10节映射封闭审计原因。已清理删除200、待清理202；下载no-store。JSON-only415文案只用于JSON入口，绝不输出文件/URL/异常正文。
- [ ] GREEN：同命令通过；实机所有路由由 Task 8 再验。未经内容接收先检查角色与并发门；没有浏览器Authorization透传。
- [ ] 提交：feat(course): expose safe attachment api routes。

### Task 6：编排、未知提交恢复与回收

**Files:** 新建 A/{AttachmentCoordinator,AttachmentReconciler,AttachmentFailureRecorder}.java；测试 PT/{AttachmentCoordinatorTest,AttachmentReconcilerTest,AttachmentAuditTest}.java。不修改既有 AuditOperationRunner。

**Interfaces:** Consumes Task 1/2/4；Produces @TransactionAttribute(NOT_SUPPORTED) coordinator：
upload(String bearerToken,String uploadId,InputStream content,Long contentLength,AuditContext) → UploadOperationView；
deletePatient(String bearerToken,String fileId,AuditContext) → AttachmentDeleteView；
authorizeDownload(String bearerToken,String fileId,AuditContext) → DownloadAuthorizationView。
Reconciler.runBatch(Instant now) → int（@Schedule 每分钟、默认单实例，返回处理条数）；FailureRecorder.record(AuditContext,CurrentActor,AuditReasonCode) → void（REQUIRES_NEW，只有已确定未成功业务才调用）。

- [ ] 写 claimedUploadIsExclusive、committedAvailableResponseLossPreservesObject（确认AVAILABLE后DELETE次数0）、databaseUnavailableAfterPutDoesNotGuessCommitOutcome（查询失败仍不DELETE）、publicationRollbackAbortsThenDeletes（先新事务abort后DELETE）、expiredBearerAfterStoreWaitCannotPublishOrIssueNewUrl、downloadAuditFailureReturnsNoUrl、physicalDeleteFailureDoesNotCreateBusinessFailure（202且只有SUCCESS）、presentationFailureAfterAvailableDoesNotAppendFailure。
- [ ] RED：M -pl paas -am -Dtest=AttachmentCoordinatorTest,AttachmentReconcilerTest,AttachmentAuditTest -Dsurefire.failIfNoSpecifiedTests=false test；预期缺失编排或顺序/审计断言失败。
- [ ] 实现认领→无JTA接收/校验/PUT→重新 AuthService.authenticate 同一token→短事务发布。未知提交先新事务查状态，AVAILABLE保留；库不可读只报告结果待确认。签发先候选/HEAD/签名，重新认证，再事务锁/版本/成功审计；expiresAt固定为issuedAt+300秒，过期不返回URL。
- [ ] 写 restartedReconcilerFindsPersistentPendingWork、latePutAfterAbsentHeadIsEventuallyRemoved、unknownEnumeratedKeyIsNotDeleted、backoffIsOneTwoFourEightSixteenThirtyMinutes、cleanupBatchAtMost100AndTombstonesRecheckedAfterThirtyMinutes、attemptFenceRejectsLatePublication。实现持久墓碑、条件认领、归属验证的受控分页枚举、安全数量告警；所有临时文件finally精确清理。
- [ ] GREEN：同命令通过；Task 8 真容器证明存储调用无活动JTA、真实重启/迟到PUT/故障最终双边收敛，不能只凭注解或Mock。
- [ ] 提交：feat(course): coordinate attachment recovery and cleanup。

### Task 7：Portal multipart 与可访问页面

**Files:** 新建 W/{AttachmentServlet,AttachmentWorkspace,AttachmentMultipart,AttachmentDownloadPolicy,PreparedPortalFile,AttachmentServletInitializer}.java、course/portal/src/main/resources/META-INF/services/javax.servlet.ServletContainerInitializer；修改 W/PaasClient.java、course/portal/src/main/java/com/medcloud/course/portal/factory/CloudServiceFactory.java；新建 course/portal/src/main/webapp/WEB-INF/views/{attachments,attachment-download}.jsp；修改同目录 patient.jsp/doctor.jsp/hospital.jsp/platform.jsp；测试 WT/{AttachmentPortalTest,AttachmentMultipartTest,AttachmentJspTest,AttachmentClientTest}.java；必要交互测试只改 course/portal/src/test/js/portal.test.cjs。

**Interfaces:** Consumes Task 1 DTO/八路由；Produces PaasClient.putBinary(String path,String token,Path file,long maxBytes,int totalTimeoutSeconds) → JsonNode（响应上限1MiB，上传为octet-stream）；AttachmentWorkspace.load(HttpServletRequest,String token,Role) → void；AttachmentMultipart.read(HttpServletRequest,Path tempDirectory,long maxBytes,int deadlineSeconds) → PreparedPortalFile；新增 W/PreparedPortalFile.java（Path path,String fileName,String contentType,long sizeBytes,String sha256,String uploadKey,String targetId，AutoCloseable清理自己文件）；AttachmentDownloadPolicy.requireAllowed(String url,Set<String> allowedOrigins) → URI。

Servlet 使用精确新路径 /patient/files、/doctor/files（GET详情），/patient/files/upload、/doctor/files/upload、/patient/files/delete、/files/download、/files/upload-status（POST动作/恢复）；不与原PortalServlet显式映射冲突。AttachmentServletInitializer.onStartup(Set<Class<?>>,ServletContext) → void 为 ServletContainerInitializer，配套新增 course/portal/src/main/resources/META-INF/services/javax.servlet.ServletContainerInitializer 注册文件。页面链接使用预约/病历安全UUID，PaaS仍最终授权。会话仅读取现有 accessToken/role/csrfToken。

- [ ] 写 roleCheckBeforeMultipartRead、missingOrDuplicateCsrfRejected、duplicateFileAndTargetFieldsRejected、clientCannotOverrideTenantOrTokenOrDestination、uploadTempFileRemovedOnEveryExit、slowChunkedMultipartDeadline、noJsUploadAndDeleteUsePrg、timeoutKeepsOriginalUploadKeyAndOperationId、signedUrlNeverInFlashLocationOrList、userinfoFragmentOrWrongOriginDownloadUrlRejected、fileNameAndUrlAttributeEscaped。
- [ ] RED：M -pl portal -am -Dtest=AttachmentPortalTest,AttachmentMultipartTest,AttachmentJspTest,AttachmentClientTest -Dsurefire.failIfNoSpecifiedTests=false test；预期Servlet/页面缺失或安全断言失败。
- [ ] 实现标准Servlet multipart（无第三方provider），先会话角色再有限解析、CSRF与字段白名单；同一UUID准备/内容，不因超时换键；安全会话只保留操作ID/键/非敏感状态。患者BOOKED/IN_PROGRESS上传、医生完成病历追加；空页可导航，医院冻结历史可读/写禁用。删除提示区分待清理。
- [ ] 下载POST直接呈现一次授权页面：no-store/no-referrer、显示固定有效期、允许来源HTTP(S)校验/HTML转义、rel=noreferrer/no预取；不放URL入重定向或session。管理审计标签与三事件同步但不能下钻附件。Initializer动态注册MultipartConfigElement（maxFileSize=maxBytes、maxRequestSize=maxBytes+65,536、fileSizeThreshold=0），受控临时目录，配置不合法只降级附件；Tomcat有限上传读等待/PaaS有限请求体由Task8配置并实测。
- [ ] GREEN：同测试命令通过；`& 'D:/nodejs/node.exe' --test course/portal/src/test/js/portal.test.cjs` 通过；Tomcat真实multipart/禁JS/浏览器门由 Task 8 验证，结构JSP测试不能替代观察。
- [ ] 提交：feat(course): add patient and doctor attachment portal。

### Task 9：先行隔离验收夹具与生命周期保护

本任务编号保留独立职责，但允许在 Task 1 真容器探针之前先执行必要的归属工具；它不依赖产品实现。

**Files:** 新建 course/deploy/tests/{attachments-fixture-ownership.ps1,attachments-fixture-ownership.Tests.ps1}、course/deploy/scripts/new-attachments-fixture.ps1。不修改旧审计夹具工具或运行实例。

**Interfaces:** Consumes 显式指定本机工具目录，不依赖产品。Produces New fixture script 参数 -RuntimeRoot -MySqlBin -JavaHome -TomcatHome -WildFlyHome -MinioExecutable -MinioClientExecutable（mc，仅专用实例建桶/应用账号策略）；只生成/运行新loopback实例，返回 Git忽略 evidenceRoot 的 fixture.json。对象含 ownerId/runtimeRoot/mysql(host,port,database,serverUuid,pid,executable,dataDirectory)/paas(url,pid,executable)/portal(url,pid,executable)/storage(internalEndpoint,publicEndpoint,bucket,prefix,pid,executable,dataDirectory)；凭据单独受控文件，不打印。Assert-OwnedAttachmentsFixture($Fixture,$Component) → void，Stop-OwnedAttachmentsFixture($Fixture,$Component) → void，验证PID/路径/端口/数据目录/UUID后正常停精确实例。

- [ ] 写 rejectsProtectedPortsAndRuntimeRoot、foreignPidOrDatadirCannotBeStopped、wrongUuidCannotExecuteSql、existingDirectoryIsNotOverwritten、credentialsAndUrlsNotPrinted；测试用伪实例，不动任何运行库。
- [ ] RED：powershell -NoProfile -File course/deploy/tests/attachments-fixture-ownership.Tests.ps1；预期缺失函数/保护断言失败。
- [ ] 实现盘符绝对目录归属校验、现有树/端口只读预检、动态空闲loopback端口、正常退出；后台 Start-Process -WindowStyle Hidden；禁止递归删除旧目录/全局强杀。服务二进制缺失报告未验，不能自动升级教师工具。
- [ ] GREEN：同命令全部断言通过，再创建一次新夹具验证身份记录可读且原端口进程未变；桶显式新建/匿名禁止/无版本和对象锁，最小权限应用账号单独初始化。
- [ ] 提交：test(course): isolate attachment acceptance fixtures。

### Task 8：集成实机门、浏览器和阶段交付

**Files:** 新建 course/deploy/scripts/verify-attachments.ps1、course/deploy/tests/attachments-acceptance.Tests.ps1、docs/course-labs/2026-10-12-attachments-acceptance.md；修改 course/deploy/README.md。证据/密钥/签名响应仅存 .superpowers/sdd/2026-10-12-medical-cloud-course-attachments/（先核git忽略）。

**Interfaces:** Consumes Task 9 fixture.json；verify-attachments.ps1 参数 -FixturePath -EvidenceRoot（无默认原运行库），输出脱敏 JSON 总结 found/executed/passed/failed/skipped/environment/limitations，failed或必需门skipped非0则退出非0。以独立两医院、两患者、两管理员、分配及非分配医生虚构夹具覆盖 spec 第12节九门。

- [ ] 写 mandatorySkippedGateFailsSummary、fixtureOwnershipRequiredBeforeEveryWrite、signedUrlsRedactedFromEvidenceSummary；RED：powershell -NoProfile -File course/deploy/tests/attachments-acceptance.Tests.ps1 -FixturePath <新夹具绝对路径> -EvidenceRoot <Git忽略绝对路径>；预期未实现路由/状态断言失败，缺外部工具不算预期RED。
- [ ] 实现升级/SDK探针/应用健康/HTTP两类三格式/分页/操作恢复/限额/矩阵/审计/MinIO私有与签名/故障注入门。存储无JTA用容器事务探针证据；人为注入发布审计回滚、提交响应丢失、清理失败、重启、迟到PUT；核数据库状态与存储摘要/对象数，未知对象不得称零残留。
- [ ] GREEN：M clean verify（列出默认可选跳过）；Task 3/4真实迁移与并发命令；`powershell -NoProfile -File course/deploy/scripts/verify-attachments.ps1 -FixturePath <新夹具绝对路径> -EvidenceRoot <Git忽略绝对路径>` 必需门failed=0/skipped=0。实机环境实际记录java/Maven/MySQL/MinIO/两容器版本与启动日志，不复制历史结果。
- [ ] 按电脑操作技能观察最新 Chrome/Edge：患者/医生正常及禁JS上传、删除、单次下载、键盘/常见笔记本布局；401/过期/403/413/415/429/503、超时恢复、医院冻结历史；截图不露密码/token/完整签名。不能访问的浏览器逐项记未验。
- [ ] 停专用MinIO后实测核心预约→接诊→病历/订单→模拟支付仍成功；从新夹具按README复现一次、核原运行实例未变。签名300秒到期有真实GET拒绝记录；注明持有者语义及非本机HTTPS/教师5.6未验。
- [ ] 独立全分支规格/质量审查，修复并重跑受影响门；提交 test(course): verify attachment services in isolated runtime。阶段报告链接设计/计划/验收报告，准确分列实现/单元/实机/浏览器与剩余全系统范围；停下来汇报附件阶段。
- [ ] 若实施后推送/合并沿用用户已有授权，先按 finishing/verification 技能核工作树、远端目标、全分支检查，保护集成树16项无关改动；使用已配置系统Git代理，不全局更改网络设置。推送、远端SHA、合并事实各自验证；任何新PR创建后必须附加到当前任务。不能为赶合并跳过实机门。

## 主代理计划自审记录

以下是各任务首轮测试的关键断言示例（测试名已在任务步骤指定；输入与夹具由所属任务构造，不作为缺失实现的占位代码）：

```java
// Task 1: metadataSerializationContainsOnlyApprovedFields
assertEquals(new HashSet<String>(Arrays.asList("fileId","sourceType","fileName","contentType","sizeBytes","createdAt")), actualFieldNames);
// Task 2: exactTenMiBAcceptedAndOneByteMoreRejected
assertEquals(10485760L, accepted.getSizeBytes());
assertEquals(413, oneByteTooLarge.getStatus());
// Task 4: concurrentReservationsRespectUploaderQuota / appendDoesNotChangeRecordOrderOrCompletionTime
assertEquals(2L, unfinishedIntents);
assertEquals(beforeRecordAndOrderDigest, afterRecordAndOrderDigest);
// Task 5: anonymousInvalidPaginationReturns401
assertEquals(401, anonymousResponse.getStatus());
// Task 6: committedAvailableResponseLossPreservesObject
assertEquals(AttachmentState.AVAILABLE, recovered.getState());
assertEquals(0, deleteInvocations);
// Task 7: signedUrlNeverInFlashLocationOrList
assertNull(response.getHeader("Location"));
assertFalse(sessionValues.toString().contains(signature));
```

```powershell
# Task 3: secondRunIsReadOnlyWithoutBackup
if ($ddlCalls -ne 0 -or $backupCalls -ne 0) { throw 'Repeat migration mutated state' }
# Task 9: foreignPidOrDatadirCannotBeStopped
if ($foreignStopCalls -ne 0) { throw 'Foreign instance was stopped' }
# Task 8: mandatorySkippedGateFailsSummary
if ($mandatorySkipped -gt 0 -and $verificationExit -eq 0) { throw 'Skipped mandatory gate passed' }
```

设计1–4（双流程/组件/权限）→Tasks1/4/5/7；5（文件/资源）→Tasks1/2/7；6（结构）→Tasks3/4；7（一致性）→Tasks4/6/8；8（删除/签发）→Tasks4/6/7/8；9（契约）→Tasks1/5/7；10（审计）→Tasks1/4/5/6/7/8；11（兼容/配置/迁移）→Tasks1/2/3/9；12（九验收门）→Task8及其引用的真实门；13–14（审阅/依据）→本计划审批及验收报告引用。

已检查共享签名/值对象由Task1定义、数据库消费者与生产者同名同类型、Review Focus五项都有拥有者测试、红绿和提交可独立检查。计划不展开算法正文，不复述全部设计；必需实机门不会被单元Mock或历史405项结果替代。状态：待用户审阅实施计划；尚未实施产品代码。
