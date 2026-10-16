# 附件服务实机验收记录（2026-10-15）

> 2026-10-16 更正与补充：下文七项属于部分 HTTP 子检查，原 `uploads-three-formats` 实际仅患者 JPG/PNG，不证明医生 PDF。驱动现为 19 必需检查、7 已执行成功、12 未验证，默认非零返回“不完整”。两项部署/验收修复已独立 scoped 审查通过，不构成完整附件或合并批准。最新环境、提交、审查、未验项详见 [10 月 16 日交接](../AI-HANDOFF-2026-10-16.md)。
>
> 本轮额外实测：医生 PDF 追加/患者读取/摘要一致、冻结病历及订单不变；真实新签名完整等待 300 秒后 HTTP 403（先前本地时间转换误判已复现并修正，原失败保留）；停 MinIO 后核心业务链恢复及重复 PASS/模拟支付 PAID（第一次尝试失败原因未明，未伪装成首次通过）。PS5.1 全新 PaaS 配置首次/只读重复/连接池通过，未完成整套从零 README 复现。Maven clean verify：548 发现、493 执行、55 可选跳过、0 失败/错误。本轮专属四组件现已正常停止，数据保留；旧文历史运行地址不可直接使用。

对象：`codex/course-environment` 附件切片（Tasks 1–9 + 5 + 6）的集成实机门。证据目录：本地 `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/`（Git 忽略；含凭据/签名材料的原始记录不入 Git）。

## 1. 环境（实际记录，非历史复制）

- 全新专属夹具 `attachments-8eaa3da66f92456f995651ad96520cc8`，由修复后的 `new-attachments-fixture.ps1` 从零创建（E 盘 ASCII 运行根；WildFly 以 E 盘暂存副本 `wildfly-stage-task8` 运行，教师 D 盘安装未改动）。
- MySQL 8.0.45（端口 63713）、MinIO 官方源码构建 DEVELOPMENT.GOGET（63714）、Tomcat 9.0.24（63716）、WildFly 10.0.0.Final（49152/管理 51062）；容器 Java `D:\Java\jdk1.8.0_341`。
- 部署本轮构建的 `paas.war`/`portal.war`（`mvn clean verify`：BUILD SUCCESS，Portal 106 项、PaaS 435 项 0 失败）；数据源 `java:/jdbc/MedicalCloudCourseDS`（REPEATABLE_READ），Connector/J 8.0.33。
- 数据：`schema.sql` + `seed-demo.sql` 虚构两医院/两医生/患者/管理员；附件迁移已升级（只读重复执行验证）。
- 身份链：`task8-running-20261015b-fixture.json`（每次写入前执行 `Assert-OwnedAttachmentsFixture`）。

## 2. 已执行门（verify-attachments.ps1，7/7 通过）

| 门 | 实际结果 |
| --- | --- |
| app-health | Portal 登录页 200；patient/doctor×2/admin/platform 五个虚构账号登录 200；匿名预约列表拒绝 |
| uploads-three-formats | 患者 JPG、PNG 经八路由完整链（准备→octet-stream 内容→AVAILABLE）；413 超限、400 错类型负例通过 |
| pagination-recovery | 页长/总数/第二页可导航；按 ID 恢复操作状态不依赖列表页 |
| limits | 同上传者第三个待处理准备 429 FILE_LIMIT_REACHED |
| matrix | 匿名坏分页 401、他院医生坏分页 403（参数解析不先于鉴权）、本院分配医生可列表、外来 uploadId/fileId 全拒 |
| audit-redaction | 管理端审计列表无签名材料、无 objectKey/存储键 |
| storage-private-signing | 匿名 LIST 403；签名 GET 200 内容可读；篡改签名 403 |

契约测试（attachments-acceptance.Tests.ps1，12 断言/3 场景）：必需门被跳过 ⇒ 摘要失败退出；外来夹具身份在任何写入前被拒且不产生摘要；证据摘要与文件不含 X-Amz-Signature/Credential/Security-Token。

## 3. 本验收发现并已修复

1. **AttachmentReconciler 生产未接线**：容器创建的 @Singleton 无测试构造器注入，定时器每分钟抛 “Cleanup transactions unavailable”。已补 @EJB/@Inject 懒接线；实机回收器随后把失败上传意图正确围栏为 ABORTED。
2. **对象键前缀不一致**：设计第5节对象键为 `course-attachments/<file_uuid>/<random_uuid>`，而生成器用 `files/` 作为桶前缀，store 的 requireKey 拒绝一切对象键（FILE_INVALID）。已改生成器为设计口径，并将实机桶策略/应用配置对齐到 `course-attachments/`。
3. configure-wildfly.ps1 在 PowerShell 5.1 下被 JVM 的 “Picked up JAVA_TOOL_OPTIONS” stderr 横幅打断（EAP=Stop + 原生 stderr）；本验收以等效步骤完成模块/驱动/数据源配置并留此工具缺陷记录。

## 4. 分列：实现 / 单元 / 实机 / 浏览器

- **已实现**：患者预约资料与医生病历追加两类附件全链（准备/内容/状态/列表/删除/下载签发）、私有桶预检、结构化匿名策略失败关闭、编排与未知提交恢复、回收器、八路由 API。
- **单元通过**：PaaS 435 项 + Portal 106 项（`mvn clean verify`）；Task2/5/6 定向套件全绿；真实 MinIO 单元门 17/17（含真实 300 秒到期，见 task-2-fix-real-minio-gate*.log）。
- **实机通过**：上表 7 门 + 3 契约场景（全新环境、真实 HTTP/MySQL/MinIO、真实部署 WAR）。
- **浏览器已观察**：无。本环境无浏览器自动化工具；Chrome/Edge 交互（正常/禁 JS 上传删除下载、键盘布局、401/403/413/415/429/503、超时恢复、医院冻结）**逐项记未验**。
- **未完成的门**（记未验，不冒充通过）：医生 PDF 追加与 300 秒到期等待在验收驱动中未含（单测真实门已有 300 秒证据）；故障注入（发布审计回滚/提交响应丢失/清理失败/重启/迟到 PUT）与停 MinIO 核心链、Task3/4 真实 MySQL 迁移并发命令、从零按 README 完整复现——均未在本轮执行。

## 5. 边界

本机 loopback HTTP，不是云端 HTTPS；MySQL 8.0.45 不是教师 5.6；两宿主浏览器、50 并发 p95、备份恢复演练、OpenAPI 覆盖、成员贡献与期末材料仍在全系统剩余范围。签名链接为五分钟持有者凭证，不承诺即时按人撤销。70 台服务器仅建模。
