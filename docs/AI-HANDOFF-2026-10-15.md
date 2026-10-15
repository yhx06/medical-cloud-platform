# 医疗云项目：下一位 AI 交接文档

> 更新（2026-10-15b）：本文件已被 [Task2 存储整改后交接](AI-HANDOFF-2026-10-15b.md) 取代。下述 I1–I4/M1“未修/Request changes”状态已过期：修复、独立复审与真实 MinIO 门见新文档；全系统范围与纪律仍以下文与新文档为准。

> 后续启动更新（2026-10-15）：用户另行要求启动演示，核心系统现已在隔离环境恢复，登录地址 http://127.0.0.1:49533/portal/login，附件关闭。最新运行身份由本地 current-fixture-path.txt 指向 demo-running-20261015-fixture.json；详情见同SDD目录 demo-start-20261015.md。下述“已正常停止”是交接完成时的历史状态，不能据此重新启动当前进程；操作前先查最新归属。未修改原教师安装或覆盖其他AI的源代码。（2026-10-15b 注：该演示运行根目录现已不存在，此段仅历史。）

更新：2026-10-15，Asia/Shanghai。本文件在当前代码测试、独立审查和测试环境正常关闭后编写。

**结论：项目未完成。数据库附件切片与门户切片限定审查通过；存储切片审查未通过，存在明确待修问题。不得据测试通过宣称附件全部完成、推送或合并。** 本轮按用户要求收尾交接，没有开始 Task5/6。

## 1. 接手先做什么

1. 进入下述 E 盘权威目录，核对实际分支、HEAD、Git status 和 AGENTS.md；不能在顶层旧 Spring/Vue 项目开发。
2. 先读本文件第4节，修复 Task2 的四项 Important 和相关无效测试；使用测试先行与独立复审。不要重写已完成 Task1/3/7/9，也不要直接跳到新业务。
3. 核对本地 SDD 报告及测试证据。历史 ledger 中较旧的“尚未派出/未审批/仍运行”描述均不是当前状态。
4. 所有运行夹具现在已停止。需要实测时先安全恢复或新建独立环境，生成新身份，绝不能直接使用保存的 PID。
5. 存储整改和部署夹具权限复现通过后，推进 Task6 编排与 Task5 API，再完成 Task8 实机/浏览器验收；之后继续全系统剩余范围。

可直接交给下一位 AI 的起始指令：

> 请先阅读 E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment/docs/AI-HANDOFF-2026-10-15.md 和本地 SDD 最新报告。继续已批准的医疗云课程版计划，先修 Task2 审查 I1–I4 并复审，不重复设计审批，不重做已通过切片。项目与新运行资料都放 E 盘，保留所有历史证据和无关改动。按实际证据区分测试、审查、实机和浏览器验收；不要将整个系统标记完成。

## 2. 权威项目、架构和授权

权威工作树：`E:/云服务工程/云服务项目-医疗云/.worktrees/course-environment`。

分支：`codex/course-environment`，已有 linked worktree，不要另建重复工作树。当前业务代码检查点：`cd70fc5211d5b9590cd6c324ae36d5eb3509d003`；本交接文档提交会位于其后，接手以 `git rev-parse HEAD` 为准。

顶层 `E:/云服务工程/云服务项目-医疗云` 是历史 Spring/Vue 路线及公共 Git 仓库，**不是当前课程版开发目录**。另一集成树 `.worktrees/complete-system` 的 `feature/complete-system-implementation` 分支曾有16项无关变更，合并前重新核实并保护。C盘原课程树保留作备份，不再开发；其 `.git` 已改为 `.git.migration-backup`。迁移记录见 `docs/PROJECT-MIGRATION-2026-10-14.md`。

架构：Java8 → Tomcat9 Servlet/JSP Portal WAR → WildFly10 JavaEE7/JAX-RS/EJB PaaS WAR → JPA/JNDI/MySQL；附件为私有 MinIO。内部 `attachment-sdk` 是 JAR，仍只有两个部署 WAR。不要换回 Spring/Vue，不升级或改教师容器模块来规避兼容问题。

用户已批准附件设计、实施计划、多智能体并行、Git写入/提交，以及完成验证后的推送/合并。私有仓库 `yhx06/medical-cloud-platform`。本轮新附件提交**没有推送或合并**；授权不等于门已通过。Git全局代理已为 `http://127.0.0.1:7890`，不要反复重配。用户要求项目放E盘、不再制作PowerDesigner图；70台服务器仅模型。只使用虚构/脱敏数据。

完整系统目标仍未完成，不能把本次交接或附件切片当作全目标完成。

## 3. 当前切片与保存点

| 切片 | 当前事实 | 后续边界 |
| --- | --- | --- |
| Task1 契约/私有SDK | 限定实现、真实Java8/WildFly JSON门及独立复审已完成；`4efd4dd` | 安全基线风险仍保留，不能重试废弃类加载方案 |
| Task3 数据库迁移 | `b97175d`，独立审查、真实MySQL8保护迁移7项已过 | Task4已补真实JPA validate；教师5.6和容器仍待验 |
| Task9 独立环境/归属关闭工具 | `03831cc`、`22b3ae5`，既有45断言与生命周期限定门 | 生成器元数据权限缺口见第4节，不能称从零已复现 |
| Task7 门户 | `82939613a6dadba02b21ffff23e8feec07fb1f3a`，精确20文件，独立审查通过 | 后端接口、教师容器、真实浏览器仍待Task8 |
| Task4 状态机/授权/审计 | `9e4c634324334737daf70551634a2da6de853f38`，精确10文件，独立规格与质量通过 | 两条非阻断补测建议；JTA代理/数据源仍待Task8 |
| Task2 存储/验证/配置 | `cd70fc5`，精确7文件，**WIP保存点，审查 Request changes** | I1–I4未修，不得标记完成 |
| Task5/6/8 | 本轮未开始实现 | API、编排恢复、实机验收仍缺失 |

Task4审查非阻断建议：现有跨预约配额并发用同一医生，医生锁也可串行，须补同患者跨医生/院场景以独立验证上传者锁；补账号停用先赢下载/发布的实际竞争，包括患者读取医生追加附件。这是覆盖建议，不是已证实授权漏洞。

Task7原审查曾误将Portal→PaaS的120秒请求上限与PaaS→MinIO的90秒单存储调用混为一谈；独立复核已撤回该Important并保留历史，**不要再为此把Portal改成90秒**。剩余Minor是构建/测试警告噪音。

## 4. 必须优先修复：Task2独立审查未通过

详细本地报告：`.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/task-2-review-final.md`。下列摘要保留在Git文档中，以免换机器丢失忽略区报告。

### I1：匿名策略识别遗漏，负例测试是假阳性

`course/paas/src/main/java/com/medcloud/course/paas/attachment/MinioPrivateObjectStore.java` 约126行只用字符串搜索识别匿名Principal，未识别合法JSON `{"Principal":{"AWS":["*"]}}`。

独立审查用实际JDK8/shaded SDK的临时假HTTP探针复现：数组匿名策略未被拒绝，实际适配器仍进入DELETE，计数1。这是协议控制流复现，**不是实际MinIO策略写入实验**。

`PrivateObjectStoreTest.java` 约40行用query等于 `versioning` 等字符串判断，而SDK实际发 `versioning=`。五个负例提前走UnexpectedOperation500而被包装503，所以“抛异常/无DELETE”不能证明执行过目标预检。作者之前的自审漏掉了此问题，报告中的完成判断已被独立审查覆盖。

下一步：先修假服务query key解析，为每个负例断言到达目标分支，加入正常私有桶DELETE正控；结构化解析策略，覆盖字符串/对象/数组、转义等Principal和Allow语义，未知结构失败关闭。观察有效RED再改实现。

### I2：验证完成与取消之间临时文件归属竞态

`AttachmentValidator.java` 约37、60–66行在工作者返回前设handedOff，但等待方可能同时超时/中断并cancel Future，导致返回路径无人接收、工作者也不删文件。当前取消路径不与handoff共享同一同步协议。

这是静态并发控制流问题，尚无确定性竞态复现测试。应共享精确路径与明确归属状态，由接收方确认接收后交接；增加可控barrier的完成/取消交错测试，证明失败最终清理、成功文件不误删。

### I3：受控枚举达到limit后仍翻页

`MinioPrivateObjectStore.java` 约95–101行把SDK `maxKeys`当整体上限，实际为每页大小。集合满后for循环仍消费后续页，可能全前缀扫描或后页失败导致本批无法推进。

应达到limit立即停止；用第二页必失败的多页假服务证明limit=1不会请求第二页，并核afterKey推进。

### I4：外层超时不等于底层HTTP已取消

`MinioPrivateObjectStore.java` 约40、133–139行的Future取消只是中断同步SDK等待；独立检查SDK字节码显示底层异步future/HTTP call未同步取消。read/write超时也不是总call deadline。当前测试只证明调用者及时503，没有证明滴流请求、重试或活跃调用在截止后结束。

应为实际HTTP调用设置总deadline及可传播取消，覆盖排队、预检、重试；补滴流/并发预检测试，观察截止后不再发新请求、活跃调用回收。迟到PUT补偿仍要实现，不能替代资源时限。

### M1及外部缺口

- 签名实际时刻在预检之后，而expiresAt从更早issuedAt计算；慢预检可能使公开的到期时间与实际签名时间不一致。统一时间来源并补慢预检检查，保留设计 issuedAt+300秒语义，不随意改公共接口。
- **生成器未同步三项最小只读桶权限**：`new-attachments-fixture.ps1` 尚缺 `s3:GetBucketVersioning`、`s3:GetBucketObjectLockConfiguration`、`s3:GetBucketPolicy`。真实GREEN使用手工修正过的专属桶策略。部署owner须在精确桶ARN追加元数据读权限，保留原prefix List条件和对象读写删除范围，加行为回归并从新环境复现，不能给adapter root凭据或扩大全桶LIST。
- SDK请求CommonsIO2.20.0，实际PaaS直接依赖2.14.0胜出。已执行存储路径通过，不等于未执行路径也兼容；若改版本由依赖owner统一裁决并重新验证Java8/打包/真实路径，不凭差异盲改。

以上问题未在本轮交接收尾中修复。后续不得以“所有测试绿”覆盖Request changes，或跳过复审启动下一阶段。

## 5. 2026-10-15测试证据及不能推出的结论

Root独立集成快照：`.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/build-snapshots/root-integrated-20261015`。由HEAD8293961的course源码加精确17个Task2/4文件构建；运行后17文件SHA256仍全匹配。

| 门 | 实际结果 | 证据/限制 |
| --- | --- | --- |
| Root Java8完整reactor `clean verify` | 501发现，446执行，55跳过，0失败/错误，exit0；两WAR打包 | `root-clean-verify-fixed-command.log`，09:41:28结束。Contract7/PaaS388/Portal106；不是全部运行时门 |
| Root独立数据库专项 | 30执行，0失败/错误/跳过，exit0 | `root-real30.log`，09:42:32结束；9个实际InnoDB等待barrier、1个RR分页snapshot，validate模式 |
| Root Node | 7/7，0失败/跳过 | `course/portal/src/test/js/portal.test.cjs` |
| Task2作者真实存储门 | 23/23，0失败/错误/跳过 | `build-snapshots/task2/task-2-final-focused-real.log`；含303.6秒真实签名到期观察，**不消除I1–I4** |
| Task2作者默认PaaS回归 | 358发现，43可选跳过，0失败/错误 | `task-2-final-paas-default-regression.log`，仅Task2冻结快照，不是全分支 |
| Task4作者修后回归 | 30专项全执行；默认PaaS365发现/50可选跳过，均0失败/错误 | `build-snapshots/task4/final-relevant-30-day15.log`、`default-full-paas-day15.log` |

Root默认55项跳过为旧可选MySQL37、Task4真实MySQL12、Task2真实存储5、SDK夹具1。12项数据库和5项存储分别有专项证据；默认skip绝不是pass，本轮没有重跑SDK实机17项或所有旧MySQL门。

曾实际发现Task4给共享H2 test persistence unit全局设RR，导致旧测试7失败；原实现者只移除该全局配置，保留Task4自身MySQL工厂RR，focused17及上述fresh全回归通过。不能把全局RR加回去。产品JNDI数据源隔离级别需Task8明确配置/证明，REQUIRED注解不等于RR。

Root初次集成命令把java.io.tmpdir参数拼接错误，Maven误识别E盘前缀，尚未构建便exit1；失败日志保留，修正为单个参数后才通过。这不是产品RED。Windows日志有编码乱码、编译弃用/Hibernate/Tomcat/Jasper警告，不能称无警告。

数据库真实测试采用RESOURCE_LOCAL，**不是WildFly EJB/JTA代理证明**。嵌入式Tomcat测试也不是教师Tomcat9.0.24或Chrome/Edge验收。I1相关假HTTP负例证据已失效，须修复后重跑。

## 6. 运行环境现状：已正常停止，数据保留

本轮测试/审查后，root对当前专属fixture逐个调用归属guard及正常Stop：Portal、PaaS、MinIO、MySQL均成功；session68407 exit0，验证对应进程和监听消失、无runtime路径进程。持活session24022随后exit0，输出Fixture processes absent。未使用强杀、未删目录、未DROP数据库。

运行根目录：`E:/medical-cloud-platform-runtime/attachments-8148fe66c0594e0b801598a7df0df08f`。

最后身份描述：`.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/fixtures/8148fe66c0594e0b801598a7df0df08f/resume-20261015-fixture.json`；`current-fixture-path.txt`仍指向这份**已停机历史身份**，不是活跃服务证明。保存PID不可再使用。

历史端口仅供恢复前检查：MySQL49530；MinIO49531/console49532；Tomcat49533/shutdown49534；WildFly49535/management51445。版本MySQL8.0.45、容器Java8u341、Tomcat9.0.24、WildFly10.0.0.Final。MinIO为固定官方源码构建 DEVELOPMENT.GOGET，不是官方预编译版本。

私有配置仅给路径，不输出内容：runtime中的 `credentials.json`、`mysql-client.cnf`、`task4-test-database-20261015.json`、`task4-lock-observer-20261015.json`，均应保留受限ACL。业务测试库 `course_attachment_test_8148fe66c0594e0b801598a7df0df08f`；另有新建的 `medical_cloud_course`。不得让测试create/drop这些已存在库。Task4只操作自己测试数据并使用validate；observer只有两个performance_schema表SELECT权限，不得扩业务用户或使用root EntityManager。

恢复时先读同SDD目录 `resume-e8148-20261015-once.ps1`：它输出文件已存在，**不可原样再运行**。基于最后day15身份创建新的单次helper及新输出名，先CheckOnly验证owner、离线UUID、路径、ACL、旧进程不存在和所有端口可用，再恢复同一数据目录；保存旧描述、不覆盖。每次SQL/存储写入、部署/关闭前执行 `course/deploy/tests/attachments-fixture-ownership.ps1` 中的 `Assert-OwnedAttachmentsFixture`。WindowsPS读取JSON用 `[IO.File]::ReadAllText`，避免UTF8中文路径误解码。

运行根使用E盘英文目录，是已复现WindowsMySQL将GBK路径字节标记utf8导致guard不匹配的规避；**不能放宽归属验证**。历史进程随会话结束消失的具体原因未定；跨会话先查实际状态，不依赖旧执行句柄，也不因超时就重复启动。

## 7. 下一阶段规则和全系统待办

附件整改后：Task6实现NOT_SUPPORTED编排、上传认领/真实解析/PUT后重新认证、短事务发布、未知提交先新事务查库；AVAILABLE不得猜失败删对象，库不可读也不能猜。持久墓碑、attempt/version fencing、迟到PUT回收、退避、重启恢复均需真实验证。Task5实现设计八路由、认证→父资源授权→分页解析、稳定错误和安全审计；不得靠假的coordinator让测试绿。Task5/6先冻结接口、独占文件，最终联合GREEN。

Task8必须：实际双容器/CDI/JTA代理、数据源RR、两类三格式上传、替换ID/角色/租户矩阵、并发及回滚、提交响应丢失、迟到PUT/重启/删除失败、真实签名/脱敏日志、Chrome/Edge及无JS上传/删除/单次下载、从零环境复现、停MinIO核心预约→接诊→病历/订单→模拟支付仍成功。必需门skip必须失败退出，不能用Mock或历史证据代替。

业务边界：患者BOOKED/IN_PROGRESS可上传，COMPLETED/CANCELED不可；患者误删仅原上传者、医院启用、BOOKED且未开始。医生只追加本人本院已完成病历，不修改原文/完成时间/订单。医院停用拒绝新写但允许有效账号历史授权读取。管理员不得下钻内容、文件名、object key或签名URL。uploadId/fileId必须同一持久行。URL不进列表/session/flash/Location/日志；签发审计不表示下载完成，5分钟链接是持有者凭证，不承诺即时按人撤销。

全系统仍保留：病情补充、病历草稿/更正版本、医院及管理员开通/状态、人员资料、跨号源患者时间冲突、支付流水唯一性、订单取消语义、完整分页/按ID恢复；处方与原需求排除冲突、可选评价/AI范围须按已批准材料核对，不擅自实施或删减。另有50并发/p95≤2秒、备份真实恢复、API/OpenAPI与设计说明、云资源/HTTPS授权、完整浏览器主流程、期末报告/PPT/演示/备用环境/真实成员贡献。

完整范围权威清单：`docs/course-labs/2026-10-04-full-scope-inventory.md`。MySQL8不能称教师5.6已验，本机HTTP不能称云端HTTPS已验；不要虚构贡献或真实医疗能力。

## 8. 材料、工具、安全与协作纪律

优先材料：

- `docs/superpowers/specs/2026-10-12-medical-cloud-course-attachments-design.md`（已批准）。
- `docs/superpowers/plans/2026-10-12-medical-cloud-course-attachments.md`（已批准，旧末尾审批文案不是新阻塞）。
- `.superpowers/sdd/2026-10-12-medical-cloud-course-attachments/progress.md` 最新顶部checkpoint及 `task-N-brief.md`、`task-N-report.md`、`task-2-review-final.md`、`task-4-review-final.md`、`task-7-review.md`。
- `root-verification-20261015.md`、`security-audit-20261013.md`、`tools-source-build-20261012/provenance.md`。
- `.superpowers/full-system-goal-progress.md` 最新顶部记录。

工具：Maven `D:/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn.cmd`；构建JAVA_HOME `D:/MyEclipse 2017 CI/binary/com.sun.java.jdk8.win32.x86_64_1.8.0.v112`；容器Java `D:/Java/jdk1.8.0_341`；Node `D:/nodejs/node.exe`；GitBash `D:/Git/bin/bash.exe`。只设置单命令环境，临时目录/快照放E盘，不改全局PATH/JAVA_HOME。Maven示例：`-f <独占快照>/course/pom.xml "-Djava.io.tmpdir=<快照>/.tmp" clean verify`。默认回归前unset可选COURSE_MYSQL/fixture环境，否则旧测试可能用不匹配的测试库命名规则。

并行纪律：root唯一操作Git index/提交；每个实现者只写独占文件，测试使用冻结已提交course源码加自己overlay的E盘独立快照。独立快照可并行Maven，不能在共享main target同时clean/build。审查者不实现、不派自己的子代理；修复交回原作者。原Task2/4作者句柄为 `/root/attachments_task2_continue_fallback`、`/root/attachments_task4_continue_fallback`，此轮均已结束；跨会话句柄不可用时单一接管者读取现有WIP/报告，不重写、不复活多个写入者。

保留未跟踪 `docs/course-architecture/Medical Cloud API Probe.eam`；原需求、图、历史证据/备份、其他脏树不可顺手清理或暂存。严格不碰 `C:/Users/yhx/.codex/task8-runtime-20260928`、3318/8180/8188/10090、教师安装及其他数据库/存储进程。禁止全局强杀、git reset --hard、checkout --、DROP恢复或递归清理宽泛目录。

Task1最终私有重定位SDK保持教师RESTEasy和公共注解兼容；没有产品 `jboss-deployment-structure.xml` 覆盖。废弃的直接Jackson隔离曾导致VerifyError或原RegisterRequest注解回归，不重试。公共Jackson2.15.4曾有14条OSV命中，教师provider2.5.4及旧容器/JDK风险另存，不能称整体无漏洞。MinIO固定官方source commit `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`，社区归档、无维护/AGPL/仅虚构loopback验收限制必须披露，不可生产使用宣称。

`.superpowers`为Git忽略的本地证据，push不包含它。跨机器要安全传递报告/必要证据或重新验收，不能将整个运行目录、凭据、token、签名URL加Git。当前交接只保存本地检查点，没有创建PR、推送或合并；之后必须修复审查阻断、跑完相应必需实机门及全分支审查，核远端SHA/目标和集成树脏改动再执行。若创建PR须附加到当前任务。
