# 医疗云后续全部开发与验收任务（2026-10-19）

> **最新用户要求：基本Demo优先提交。** 当前先收尾已进行的小修复、课程版README/演示指南、构建和推送；H03高级实际矩阵/JTA/故障恢复及其他待办不删、不假通过，后续AI继续按本文实现并交回review。见[基本Demo指南](DEMO.md)。

> **最新检查点：** [修复状态与接续顺序](reviews/2026-10-20-repair-checkpoint.md)记录未提交修复、实际离线测试、用量中断作者及剩余实机门；当前不能直接Execute。

> **2026-10-20 原AI复核：** [执行前审查](reviews/2026-10-20-h03-pre-execution-review.md)发现H03及迁移/竞态/分页阻塞；下述历史进展不构成Execute许可。先修复、测试和复审，不删除旧失败，也不把未测门改成通过。

> 给下一位AI：先读 [当前接续入口](AI-HANDOFF-2026-10-20.md)。使用已确认计划逐项执行；未确认的新业务先设计、确认后再实施。可以用子代理并行独立文件，但真实服务/SQL/故障由协调主代理串行操作。每项需要测试、独立代码审查和准确状态记录。

> **2026-10-20 进展（下午更新）：** H01 已闭合；H02 环境创建与正常停启已实测；H03 真实 MySQL 32/32 已通过，四参数验收驱动 `verify-hospital-onboarding.ps1` 及其离线测试已交付（提交 `a94ef1b`，10 例离线全过，尚未对真实 fixture 实机执行）；H04 双浏览器 24/24、禁 JS/390px/日志扫描已通过，Task 8 的历史 opaque ID、停用会话退出、整分支审查与门 6 仍未闭合。R03–R08 已交付（R04 同患者跨院半开区间、R05 不取消订单仅唯一流水、R07 维持排除/可选未实施、R03 最小可写资料、R06 完整分页、R08 OpenAPI+运维追踪）。R01/R02 仍待真实验收；R09–R12 未开始。详细接续见 [AI-HANDOFF-2026-10-20](AI-HANDOFF-2026-10-20.md)。

**目标：** 完成医院开通剩余门、已实现附件/诊疗的未闭合验收、系统尚缺的约定功能和期末交付，形成可供原AI review的源码与证据包。

**架构：** 维持Java8、Tomcat9 Servlet/JSP、WildFly10 JAX-RS/EJB/JTA/JPA、MySQL、私有MinIO的课程双WAR架构。新增功能按契约→保护迁移→业务事务→API/审计→门户→真实验收纵向切片，不重写既有系统。

**依据：** [完整范围历史清单](course-labs/2026-10-04-full-scope-inventory.md)含34FR/9NFR/9AT及编号外需求；[医院已批准设计](superpowers/specs/2026-10-18-medical-cloud-hospital-onboarding-design.md)与[八任务计划](superpowers/plans/2026-10-18-medical-cloud-hospital-onboarding.md)；[诊疗设计](superpowers/specs/2026-10-16-medical-cloud-clinical-supplements-design.md)与[计划](superpowers/plans/2026-10-16-medical-cloud-clinical-supplements.md)；[附件设计](superpowers/specs/2026-10-12-medical-cloud-course-attachments-design.md)与[计划](superpowers/plans/2026-10-12-medical-cloud-course-attachments.md)。本文是跨切片接续路线，不替代尚未确认业务的详细设计批准。

## 全局规则

- 所有项目产物在 `E:/云服务工程/云服务项目-医疗云` 内；权威本机源码 `.worktrees/course-environment`，不要开发旧Spring/Vue主目录。
- 不修改教师安装/保护端口；不重做PowerDesigner，不启动70台VM；只虚构/脱敏数据，不是真实医疗/支付系统。
- 不上传凭据、正文、原始日志、行快照/摘要；不共享Maven target；不并发迁移、故障注入或浏览器写同一个实际环境。
- 不用Hibernate create/update替代生产保护升级器；保留旧库、升级前备份、失败操作编号和精确恢复记录。
- 每个mandatory门缺失、skip、失败、未验证都不能计通过；JPA RESOURCE_LOCAL/H2不能冒充WildFly JTA，MySQL8不能冒充5.6，IAB不能冒充Chrome/Edge，本机loopback不能冒充云部署。
- 原病历/订单冻结、更正历史每页最多5条、草稿私有等已确认规则不回退。不得用降低安全上限或删除旧断言换全绿。

## 执行顺序与完成标准

先完成H01，再完成H02→H03→H04。R01/R02可准备离线代码，但真实操作与医院环境分开、串行。资料、分页、文档可按独占文件并行；预约冲突/订单/支付事务变更应顺序整合。R03～R08业务稳定后做性能、恢复、全量验收和期末报告。

复选框表示未来工作，**当前均未整体关闭**。每项关闭时记录基线/最终提交、精确文件、实际命令/native退出、执行/skip数量、审查及仍存在的限制，不只把框改勾。

## H01：修复当前两个超时回归，解除整合阻塞

**起点与文件：**

- PaaS `course/paas/src/test/java/com/medcloud/course/paas/attachment/PrivateObjectStoreTest.java`；必要时检查对应 `attachment/MinioPrivateObjectStore.java`，不要无证据改生产逻辑。
- Portal `course/portal/src/main/java/com/medcloud/course/portal/web/PaasClient.java` 和 `course/portal/src/test/java/com/medcloud/course/portal/AttachmentClientTest.java`。
- 机制、旧失败与诊断边界见接续入口§4；私有诊断副本不能部署。

- [ ] 按诊断证明修正MinIO测试语义：每调用独立deadline，持有preflight monitor直到调用耗尽预算；实际等待/有界join、释放后worker排空、0后续网络和对象工作；保留原单请求/滴流/取消时限。先证明反例能抓住删除锁后deadline检查的变异，再验证真实代码。
- [ ] 为Portal写确定性反例，覆盖响应持续有字节、watchdog被同步close阻塞/占用及并发请求，不能仅复跑旧用例至绿。根据证据做最小期限修正，检查finally关闭和worker回收是否仍能拖延调用者。
- [ ] 保留二进制精确字节、大小检查、1MiB JSON、可信token、不跳转、不隐式重放、总期限和阻塞写入安全；如需更换传输依赖/并发模型，先说明设计及影响，不能偷偷加第三服务。
- [ ] 执行聚焦RED/GREEN后在独立短路径副本跑完整 `mvn -o -f course/pom.xml -Dcourse.test.wildfly.home=<教师WildFlyHome> clean verify`，报告所有可选跳过及具名失败；独立审查修复和测试本身。不覆盖原失败日志。

**关闭标准：** 两问题有可解释根因、有效回归、全项目构建/native0；不存在靠放宽上限/选择性排除失败达成的成功。保留诊断不能精确复现旧Portal调度的限制。

## H02：完成医院Task6新独立005环境

**现有WIP：** `course/deploy/scripts/new-hospital-onboarding-fixture.ps1`、`course/deploy/tests/hospital-onboarding-fixture.Tests.ps1`。继续完善，不从零覆盖。**当前未审查、未实际运行，不直接Execute。** 详细入口/流程以已批准八任务计划Task6为准。

- [ ] 阅读停点报告及源码，完成离线原生PS5.1反例：owner/PID/创建时间/监听/UUID、ACL/reparse/路径、已有目录与重复编号、意图先写、身份丢失不覆盖journal、整个ZIP先验证再提取、实际Java8/Maven编译前置失败、真实Process.ExitCode而非旧LASTEXITCODE、环境完整恢复。
- [ ] 冻结并测试无main导入与 `Read-OnboardingFixtureContext`、`Assert-OnboardingIdentity`、`Assert-OnboardingMysqlLive` 的实际接口；不复用旧offset专用guard，不让默认入口发SQL/login/claim。独立审查整个创建器，不只检查函数名存在。
- [ ] 核对固定源码导出commit/UTF8/短路径/Git换行、全部依赖及clean verify均先于凭据和服务；源码不同不得默默绕过pin。IAM导入要判断added用户/策略/绑定和failed/skipped/removed，不只看native0；无秘密argv/全局mc配置污染。
- [ ] 协调主代理先完成正常权限审查，再用新32hex编号、新RuntimeRoot启动loopback主MySQL/MinIO/Tomcat/WildFly，固定同一40hexSourceCommit两个WAR。辅助独立MySQL取309b675精确004升级到005；不操作原临床库。
- [ ] 实测13张表/JPA validate、主/辅助离线及liveUUID/数据目录、私有ACL、双WAR业务健康；正向正常停/启生命周期，恢复后更新身份并明确running/stopped。失败保留目录，不自动force-kill或DROP。

**关闭标准：** 离线主代理复测+独立审查+新实例实际结果及正常生命周期；manifest的sourceCommit/schema005/全部身份可信且新鲜。仅看监听/欢迎页或作者离线绿色不算关闭。

## H03：完成医院Task7真实MySQL和API/JTA验收

**现有草稿：** `course/paas/src/test/java/com/medcloud/course/paas/MySqlHospitalAdministrationTest.java`（32发现、32跳过）。**待新建：** `course/deploy/scripts/verify-hospital-onboarding.ps1`、`course/deploy/tests/hospital-onboarding-acceptance.Tests.ps1`。这些不是已交付工具。

- [ ] 先强化草稿的账号行级不变、每个writer精确字段、观察者URL/最小权限、失败线程/连接/锁释放及MySQL版本查询；独立审查Java测试。执行REQUIRED=true缺配置应失败的真实负例，不能因助手清空COURSE_*把它又变成optional skip。
- [ ] 冻结以下32项清单，26项竞争均要精确参与连接的具名InnoDB等待，不以sleep/TimeoutException冒充：

| 必需案例组 | 数量 |
| --- | ---: |
| schemaJpaValidate | 1 |
| 同key开通/增补两提交方向 | 2 |
| 用户名大小写与PATIENT/DOCTOR各两方向竞争 | 4 |
| 两个最后管理员互停两方向 | 2 |
| 停用与科室/医生/号源create/update六入口各两方向 | 12 |
| 冻结与增补/启用/停用各两方向 | 6 |
| SUCCESS审计失败回滚：开通/增补/启停 | 3 |
| 冻结且账号停用后的同键恢复 | 1 |
| 零启用管理员历史数据的恢复 | 1 |
| 合计 | 32 |

- [ ] 新专属测试库 `course_onboarding_test_<32hex>`、狭窄业务账号、独立锁观察账号；真实MySQL/JPA validate，清理只该库已知表DML，不用create-drop。协调主代理运行 `mvn -o -f course/pom.xml -Pmysql-integration -pl paas -am -Dtest=MySqlHospitalAdministrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，精确32执行/0skip/0failure-error和26等待标签全部有证据。
- [ ] PowerShell入口按计划四参数 `FixturePath,EvidenceRoot,NewRunId,Execute`；默认无SQL/login/claim、未验并退出4；Execute使用CreateNew消费。离线RED/GREEN覆盖缺失/重复/skip门、错身份、故障触发器被替换、写前意图、身份丢失不覆写、精确恢复、安全错误、严格摘要，不能用永久拒绝Execute的stub冒充完成。
- [ ] 新拥有环境实际验证四API/全角色、原子开通/幂等/冲突/冻结维护/最后管理员、旧JWT停用生效。实际EJB/JTA注入审计失败，12张非审计表逐行NULL/HEX比较只在内存；独立FAILED、锁释放、精确临时触发器恢复与健康后续必须一起证明。长操作后重认证，不延长生产TTL/自动重放业务POST。
- [ ] 实际迁移反例包括重复只读、异构/部分DDL拒绝、备份失败先于DDL、旧升级器拒005。MySQL8和教师5.6分开记录，不凭离线替身补实机门。主代理复核、独立审查后提交。

**关闭标准：** 全部mandatory真实执行，状态摘要不能有missing/duplicate/skip/unverified，恢复未知不得成功；源码32参数化用例编译通过不是通过32实机案例。

## H04：医院Task8浏览器、最终回归及整合

- [ ] 在同一验收版本用真实Chrome和Edge分别走平台开通、增补、启停、最后管理员阻止、冻结医院修复、旧JWT失效、幂等重试/响应丢失后手动同键原密码恢复。
- [ ] 桌面和390px、真实禁JS原生表单、Tab顺序/label、中文/HTML转义、CSRF、重复参数、密码失败清空、结果PRG刷新/回退/不同session/十nonce上限；保留失败输入中的非秘密字段和原operationKey，密码不能进响应/缓存。嵌入式JSP不是这一步的替代。
- [ ] 最后一次完整clean verify、迁移及核心预约→诊疗→订单→支付→附件/更正/审计回归；更新部署/API/验收文档，运行版本与验收源码一致。
- [ ] 整批独立review，处理接续入口所列Minor及任何新Critical/Important；不要把当前局部批准当最终whole-branch批准。检查目标集成树原有脏改动，再依用户授权安全推送/合并，不force、不覆盖他人改动。

**关闭标准：** 计划八项真实门闭合、无遗留触发器/进程归属问题、浏览器身份明确、无错误通过统计；只关闭医院切片，不宣称全系统完成。

## R01：闭合已经实现的诊疗增强验收

**文件入口：** `consultation/ClinicalSupplementService.java`、`api/ClinicalSupplementResource.java`（均在course/paas主包下）；`MySqlClinicalSupplementTest.java`；`course/deploy/scripts/verify-clinical-supplements.ps1`及相应离线测试；[诊疗验收指南](course-labs/2026-10-16-clinical-supplements-acceptance.md)。

- [ ] 先核对本机私有SDD的最新覆盖及已消费编号；历史HTTP23通过/30未验证、迁移七门已闭合、旧九例仅RESOURCE_LOCAL。没有旧私有证据时从已提交源码新造数据复验，不能移植旧passed字段。
- [ ] 单独向用户说明并确认旧临床十例运行助手完整预检重构方案（Fix4未批准）；解决代码导出/参数作用域/短路径/严格换行pin的整体预检，再用新编号验十例及新增draft-before-stale-complete真实等待。医院新创建器的批准不等于旧临床助手可改。
- [ ] 用新目标补更正实际EJB/JTA、独立FAILED、精确恢复及健康；请求前重新认证，保留旧401失败不改写。完成冻结/停用与补充/草稿/更正/完成的角色矩阵和双方向竞争。
- [ ] 补停MinIO时补充/草稿/更正仍可用的真实边界、53门其余未验项目，以及Chrome/Edge、禁JS/键盘、CSRF、中文转义、409保留输入。更正历史每页最多5条与原病历/订单不变逐字段验证。
- [ ] 独立切片review，更新精确门状态而不重写历史。

**关闭标准：** 已批准计划的mandatory真实门全部闭合；不能只重交已实现代码或把新反向源码追认成旧九例已测。

## R02：闭合已经实现的附件验收

**文件入口：** `course/paas/.../attachment`、`api/AttachmentResource.java`、Portal附件Servlet/JSP；`course/deploy/scripts/verify-attachment-*.ps1`及[附件验收指南](course-labs/2026-10-12-attachments-acceptance.md)。

- [ ] 对照19必需门，历史7通过/12未验证不等于完整；核认单独真实PDF/300秒到期/停MinIO观察的来源和版本，不能直接导入驱动成功。H01两个超时问题先处理。
- [ ] 患者本人预约上传/允许阶段删除与医生本人原病历追加分别验证；跨院/跨患者/非原医生、冻结/停用、真实内容/伪扩展名、大小/像素/页数、路径和私有桶/前缀/短期授权保护按已批准设计逐条执行。
- [ ] 实际并发上传、重复content、完成/删除/上传冲突、失败元数据/对象一致性及清理重试；真实JTA与独立失败审计、存储故障降级、完整Chrome/Edge与从零复现，不把RESOURCE_LOCAL等同容器事务。
- [ ] 私有URL实际300秒失效、无匿名LIST/GET、权限不足安全失败；所有配置/依赖安全局限保留。独立整体review并关闭精确门。

**关闭标准：** FR-FILE-01～04、NFR-04、AT-06的实现和实际验收均有证据，而非仅有上传页面。

## R03：医院、医生与本人资料能力

**先设计确认，未实施。** 触及 `tenant/Tenant.java`、`DoctorProfile.java`、`HospitalDoctorService.java`、`auth/Account.java`及对应contract/API/Portal；文件名按获批设计固定后再编码。

- [ ] 从需求提取医院资料、医生职称/简介/资格字段、本人资料到底哪些可写/公开/私有；确认修改者、长度、空值、唯一性、审核/通知是否在范围，不借此增加真实资质认证。
- [ ] 形成共享DTO/校验、保护迁移（编号按届时实际schema顺序分配，不预占与其他切片冲突）、服务端授权/版本冲突/审计、四角色资料视图的独立实施计划并确认。
- [ ] RED覆盖跨院修改/角色注入、停用/冻结、超长/控制字符/HTML、公开字段白名单和旧行兼容；实现纵向切片、实机/浏览器/独立review。

**关闭标准：** 不把工作台用户名标签等同完整资料能力，也不将密码哈希/内部资质文件暴露给公开目录。

## R04：跨医生/号源时间冲突预约

**先设计确认，未实施。** 入口 `appointment/BookingService.java`、`Appointment.java`、`schedule/ScheduleSlot.java`及contract/API/Portal和保护迁移。

- [ ] 向用户确认冲突定义：患者、有效预约状态、跨医院/医生范围、区间端点是否相接可约、日期/时区、取消释放与历史处理。可提出半开区间重叠方案，但不能直接当成已批准。
- [ ] 固定锁对象/顺序和索引，防“不同号源同时检查都没冲突”的幻读；不能只加同slot唯一约束（现已有）。写相同/包含/交叉/邻接区间及取消/容量恢复测试。
- [ ] 两个不同slot/不同医生同患者两方向真实并发；与最后容量/重复预约/取消交错，精确锁观察、胜者字段/失败不扣容量/幂等恢复；API409和门户保留选择，独立review。

**关闭标准：** 真正阻止按确认定义的时间重叠，同时保留现有容量及取消语义。

## R05：未支付订单取消规则与支付唯一流水

**取消存在设计冲突，先裁决；流水独立设计后实施。** 入口 `order/PaymentOrder.java`、`PaymentService.java`、`api/PaymentResource.java`、`consultation/ClinicalService.java`及相关Portal/统计/审计。

- [ ] 取消先向用户明确：订单在完成接诊后才产生，但已批准原病历/完成订单不可被更正修改。谁能取消、哪些未付订单、是否撤销接诊、账单保留/计费/统计/审计口径需书面确认；不能仅加按钮、删病历或退回预约状态。
- [ ] 若范围确认，设计新状态/原因/操作者/时间/版本和保护迁移；支付vs取消双方向真实并发，已付拒绝、取消拒付、原病历与合法历史不变、金额及统计对账，角色/跨院/API/页面闭环。
- [ ] 唯一支付流水另定格式及生成方、全局/业务唯一、首次模拟支付同事务生成、重试保持原值、旧PAID迁移策略。只做模拟，不接真实资金服务。RED及真实并发证明一单一流水、不同单无碰撞、失败回滚和脱敏查询。

**关闭标准：** FR-ORD-04及AT-05取消后拒付闭合；流水不能被“已有paid_at幂等”替代。未裁决前保持未实现而非删除需求。

## R06：完整分页与按ID恢复

**按现有语义细化设计，未全覆盖。** 入口 DirectoryService、BookingService、ClinicalService、PaymentService及对应目录/患者/医生/医院订单API和Workspace/JSP。

- [ ] 逐路由盘点无界列表和截断100/200条路径，列出输入/输出/排序/权限；现有附件、更正分页已实现，不重做或把更正5条放宽100。
- [ ] 为缺口设计稳定唯一排序、总数/页/size上界、空页、offset溢出、按ID受权恢复；新增记录时不把其他患者/医院的数据泄露到分页/总数。不要凭UI隐藏实现权限。
- [ ] 制造超页容量数据，覆盖首/中/末/空页、重复排序键、非法/极大参数、历史记录按ID与停用/冻结只读；API与页面同步、真实中文转义/禁JS导航；演示脚本不再依赖“前100条恰好有目标”。

**关闭标准：** 所有约定列表完整可达且有界，无静默截断/无限列表；计数和详情权限一致。

## R07：结构化处方、评价与AI范围决策

这些不能静默纳入必做，也不能被遗忘：

- [x] **处方冲突：** 已裁决并维持排除。旧设计含结构化药品处方，需求书明确排除；自由文本“建议”不是处方模块，期末报告必须写明“处方排除”，不得冒充已实现。
- [x] **FR-FBK-01（P2可选）：** 已裁决为可选未实施，不造评价数据；期末报告写“P2 可选未实施”，不写成“天然降级通过”。
- [x] **FR-AI-01～04（P2可选）：** 已裁决为可选未实施，不接外部 AI、不产生密钥/预算/发送范围；AT-08 记为未实施/未验证，不是“天然降级通过”。

**关闭标准：** 已由 [2026-10-20 裁决](superpowers/specs/2026-10-20-medical-cloud-r03-r07-decisions.md) 记录用户可追溯范围（处方排除、评价/AI 可选未实施）；无纳入项需另行设计实施。

## R08：机器可读API与运维追踪

**入口：** `docs/course-architecture/README.md`、所有JAX-RS资源、`AuditRequestIds/RequestFilter/ResponseFilter/ApiFailureMapper`，门户安全错误边界和部署文档。

- [ ] 新建课程API OpenAPI文件（建议 `docs/api/course-openapi.yaml`，获批方案固定版本），覆盖当前每个公开route/method、四角色/租户、DTO/分页、业务码、request-ID、二进制上传与私有短期下载；不得误收旧Spring路由或内部测试入口。
- [ ] 自动校验规范与route清单及示例一致性；文档示例仅虚构无token/有效签名。依赖生成工具前验证Java8/教师兼容，不为文档强改运行框架。
- [ ] 盘点所有接口及跨Tomcat→WildFly错误的关联追踪；已有审计UUID不代表全部运维日志。定义可信服务端ID、入站ID处理、安全固定错误/日志字段、正文/密码/JWT/签名URL不记录；测试授权失败/500/代理非JSON/连接丢失及故障关联，不回显native诊断。

**关闭标准：** NFR-07完整覆盖有自动检查，NFR-08运维链可定位且不泄密；不要把只有Markdown目录视为OpenAPI已完成。

## R09：性能、安全矩阵与全功能双浏览器

- [ ] 在明确固定源码、工具、数据规模与隔离环境下做NFR-01：50并发号源查询、预热与测量窗口、错误率、p50/p95及计算方法，目标p95≤2秒。查询性能不能由单一容量竞争用例代替；不对真实医院/未授权公网压力测试。
- [ ] 最后一号源并发、重复支付、取消/完成/更正/附件/管理员启停/医院冻结等最终状态交错重验；逐条角色×租户×对象所有者矩阵，包含旧JWT、历史只读、分页/详情/下载，不只测试正常入口。
- [ ] Chrome和Edge各完成全主要流程，普通和禁JS/键盘/390px、错误/冲突输入保留/CSRF/转义、超时/401登录恢复、存储停机核心流程独立性。记录浏览器版本/真实surface，未连接就未验，不用IAB或DOM静态检查替代。
- [ ] 保留依赖安全核查时点和数据库范围。Java8/WildFly10/旧容器/归档MinIO是课程限制，不声称生产安全或“零漏洞”。

**关闭标准：** NFR-01/02/03/06和AT-01～07对应最终版本证据可逐项追踪，失败与未验不隐藏。

## R10：真实备份恢复、从零复现与部署边界

**入口：** `course/deploy/README.md`、schema/001～005及届时后续保护迁移、相关upgrade和owned fixture脚本、对象存储配置。

- [ ] 定义周期导出/保留策略和最小权限，数据库＋对象元数据/对象内容的一致恢复点；不把升级前一次dump等同定期备份。备份私有ACL、密钥不随公开仓库上传，恢复日志脱敏。
- [ ] 在全新独立实例实际恢复至少一次：旧行NULL/HEX逐字段、关联/账单金额与状态、文件下载内容、对象访问策略、账号安全及JPA validate、登录/核心链；记录真实原生dump/restore退出和正常关闭，不只验证文件存在。
- [ ] 从仅Git源码＋明确工具来源的干净目录按部署文档复现同版本两个WAR和存储，不能依赖某AI本机ignored helper。验证教师MySQL5.6；若环境不可得或兼容性失败，明确报告并与用户确定课程交付方案，不能伪装成8.0结果。
- [ ] 云端部署先确认用户已有资源、费用、权限、域名/HTTPS/防火墙/凭据/备份与旧组件风险；没有授权不购买/开机/暴露旧WildFly或MinIO到公网。本地成功与NFR-05云端交付分别记录。

**关闭标准：** NFR-09实际恢复和AT-09新环境复现闭合；云端是否纳入有明确记录，备用环境能用。

## R11：期末报告、设计说明、PPT和可重复演示

- [ ] 按老师实际要求组织需求追踪、IaaS/PaaS/SaaS映射、用例/组件/部署/关键时序、当前SQL/JPA结构及接口说明；无新增PowerDesigner绘图。旧ER8表/70台模型注明历史，不说成当前13表或真实70台运行。
- [ ] 汇总真实测试/性能/恢复/权限及局限，区分代码完成、离线通过、实机通过、未验证；模拟支付/虚构医疗/AI辅助声明准确。不要编造云端截图或同学贡献。
- [ ] 最终报告、汇报PPT、讲稿、演示脚本和备用环境；从新账号/新预约可重演，避免复用消费过的故障操作或依赖历史列表首100条。演示至少覆盖患者→医生→账单→支付、附件、更正、医院/平台管理与审计。
- [ ] 成员贡献由真实认领/代码/测试/文档证据填写，请用户补确认姓名与职责。提交格式、截止日、页数和PPT时长未明确时向用户询问，不编造。

**关闭标准：** 可用于实际期末提交和现场汇报，所有结论与最终版本/证据一致；文档可复制复现而非开发日志堆积。

## R12：最终整合及交回原AI review

- [ ] 校对下方全部需求追踪及每项明确排除/已验/未验，处理所有deferred Minor和Critical/Important；全分支独立审查及一次集中修复/复核，保留差异和裁决。
- [ ] 保护其他工作树与未跟踪EAM，精确提交安全源码/文档；无凭据/运行库/原始日志/大二进制。GitHub正常代理/权限通道推送后独立核实远端SHA；不force、不覆盖用户改动，未通过全部门不抢合并。
- [ ] 向用户返回下述review包，再由原AI审查。用户让原AI review不等于下一AI可以自己宣称原AI已批准。

**review返回包必须包含：**

1. baseline与最终commit、分支/远端SHA、与baseline的完整diff及精确owned文件列表；变更目的与所有用户裁决。
2. 当前各任务和34FR/9NFR/9AT状态矩阵；已实现≠已验，不在范围的P2有批准记录。
3. 可执行构建/测试/新环境部署命令；各命令native退出、发现/执行/失败/错误/skip数量；参数化MySQL清单和真实锁观察名称。只给安全汇总，不提交Surefire属性里可能含凭据的原XML。
4. 真实EJB/JTA、故障审计、12表或届时完整表集逐字段不变、精确恢复/健康/线程与连接回收证据；失败原样保留、重测新编号，不导入旧passed。
5. Chrome/Edge实际流程与版本、50并发p95、恢复演练、教师5.6/云端证据或明确限制；新实例状态和安全恢复说明，凭据在本机私有文件。
6. deferred/parked问题、风险与未完成项、源代码和文档是否一致、是否推送/合并及目标树脏改动保护；附供原AI聚焦审查的高风险文件索引。

## 全需求覆盖索引：不能遗漏或静默缩范围

下表是当前代码/验收层概况，不是最终通过报告；详细历史以范围清单和各专项为准。

| 原功能编号 | 当前接续归属 |
| --- | --- |
| FR-AUTH-01/02/03 | 核心已实现；H03/H04及R09保留新增角色/租户/停用回归 |
| FR-AUTH-04 | 既有审计切片有专项通过；H03/R01/R02/R08/R09扩展及最终复验 |
| FR-TEN-01/02/03/04/05 | 既有医院目录/科室/医生/号源/医院启停已实现；H03/H04并发与历史保护，R03资料；平台开通是额外已约定需求 |
| FR-APT-01/02/03/04 | 核心已实现；R04跨时段冲突、R06完整列表、R09最终回归 |
| FR-APT-05 | 已实现病情补充，R01闭合验收 |
| FR-CON-01/02/03/04 | 核心已实现；R01/09与草稿、更正/附件联动 |
| FR-CON-05 | 更正已实现，R01闭合；历史5条例外保留 |
| FR-ORD-01/02/03 | 已实现模拟账单/支付/查询；R05流水与状态、R06分页、R09回归 |
| FR-ORD-04 | R05先规则裁决再实现取消 |
| FR-FILE-01/02/03/04 | 业务已实现，H01/R02闭合验收 |
| FR-STAT-01/02 | 已有统计专项；R05新状态对账、R09最终聚合复验 |
| FR-FBK-01 | R07可选决策，不冒充已做 |
| FR-AI-01/02/03/04 | R07逐项可选决策及人工确认/降级 |
| 编号外已设计项 | H02～04医院/首管理员；R01私有草稿；R03医院/医生/本人资料；R04跨医生时段冲突；R05唯一流水；R06完整分页/按ID；R07处方冲突；R11交付物 |

| NFR | 关闭任务与证据 |
| --- | --- |
| NFR-01 性能 | R09，50并发查询p95≤2秒 |
| NFR-02 一致性 | H01～04/R01/02/04/05/09，真实事务/幂等/故障/恢复 |
| NFR-03 安全隔离 | H03/04/R01/02/03/06/08/09，最终权限矩阵 |
| NFR-04 附件 | R02，真实内容/大小/私有存储与下载 |
| NFR-05 云端 | R10，先资源/费用/风险授权，与本地分开 |
| NFR-06 浏览器 | H04/R01/02/09，真实Chrome与Edge全主要流程 |
| NFR-07 文档/API | R08/R11，OpenAPI覆盖及设计/部署说明 |
| NFR-08 运维 | R08/R10，请求追踪/安全日志/恢复 |
| NFR-09 备份 | R10，周期方案与至少一次真实恢复 |

| 验收编号 | 最终复核任务 |
| --- | --- |
| AT-01 核心全链 | H04/R09/R11，最终版本完整演示 |
| AT-02 并发容量 | R04/R09，真实最后一号源竞争 |
| AT-03 角色 | H03/04/R01/02/R09 |
| AT-04 跨院 | H03/04/R01/02/03/06/R09 |
| AT-05 支付 | R05/R09，幂等与取消后拒付规则 |
| AT-06 附件 | H01/R02 |
| AT-07 统计 | R05/R09，含新状态口径对账 |
| AT-08 AI故障 | R07，仅用户纳入AI后实际验证，否则明确范围裁决 |
| AT-09 部署复现 | R10/R11，新环境、存储、迁移、备用环境 |

到此覆盖34项编号功能、9项非功能、9项验收及编号外已约定范围。下一位AI必须在实际完成后更新状态，不能因为本文件存在就宣称任务已完成。
