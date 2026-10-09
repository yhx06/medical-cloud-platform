# 脱敏审计专项验收

设计日期：2026-10-04；本地实测日期：2026-10-08/09。审计功能已实现，以下严格区分代码、真实数据库、EJB/HTTP、实际 Tomcat 与浏览器证据。可用工具支持的浏览器检查和临时副本正常收尾已有实际证据；真正禁用脚本的浏览器检查仍未执行，独立评审尚待完成，不能据此宣称专项无条件验收或整个医疗云项目完成。

## 范围与运行环境

本轮仅修改课程测试、验收脚本和相关说明；原 Spring/Vue 工程、教师安装、既有运行副本和 PowerDesigner 文件未改。所有账号、医院、诊疗与金额为虚构数据。新建 loopback MySQL/Tomcat/WildFly 副本，避开保护实例的 3318/8180/8188/10090；JDK 1.8.0_112、Tomcat 9.0.24、WildFly 10.0.0.Final、MySQL 8.0.45、Connector/J 8.0.33。两个 JVM 和 JDBC 使用 Asia/Shanghai。

短 ASCII 临时 datadir 解决 Windows MySQL 的路径边界；独立实例的规范库名仅用于双容器和迁移验证。JUnit create-drop 只允许唯一 loopback `course_audit_test_<32hex>`，拒绝生产库。原八表、现有状态机/锁顺序/首次付款时间和 JPA `validate` 保留；升级只加审计表、不补造历史、不重新 seed。

验收期间新副本曾在外部中断后无监听/无进程，原因未确证，不能将系统关闭请求事件写成已证实重启。恢复前核对原 datadir/UUID、专属端口、配置与已部署 WAR 身份；未初始化、seed、升级或改业务行。恢复后九表/65列/零触发器及末次八表完整摘要一致，实际 JTA/Shanghai 重新读回。旧身份、旧日志、数据与恢复证据均保留。

## 接口与隐私契约

- 医院管理员 `GET /paas/api/hospital/audit-events` 固定本院；平台管理员 `GET /paas/api/platform/audit-events` 可选 `tenantId`，不传时为全部授权范围。停用医院仍可查询历史，普通患者/医生不得访问管理员审计查询。
- `from/to` 按北京时间自然日，默认近30天，允许1–366天；`page=1/size=20`，size1–100，long offset 不超过 Integer.MAX_VALUE。认证先于参数解析，拒绝重复、未知及越界参数。
- 13类事件为 LOGIN、TENANT_STATUS_CHANGE、DEPARTMENT_CREATE/UPDATE、DOCTOR_CREATE/UPDATE、SLOT_CREATE/UPDATE、APPOINTMENT_BOOK/CANCEL、CONSULTATION_START/COMPLETE、PAYMENT_PAY。结果仅 SUCCESS/NO_CHANGE/FAILED；重复付款保留首次 SQL 持久化时间并记 NO_CHANGE。
- 关联 UUID 由服务端生成，不信任客户端 X-Request-ID。失败响应、响应头与唯一审计记录对账；失败目标ID为空。审计读取不生成事件。
- `audit_event` 的受信任标量关联仅用于内部对账；查询 DTO/页面没有 actorId/objectId、患者身份、临床正文、密码、令牌或明细入口。日志告警只输出 requestId 和安全类型，不输出失败正文。

## 数据库与实际事务证据

新增八个真实 MySQL 用例，固定名称及最低数量，零失败/错误/跳过：

1. committedSuccessVisible
2. rolledBackMutationHasNoSuccess
3. forcedSuccessInsertFailureRollsBackBusiness
4. duplicateRequestEventIsRejected
5. failureStoresNoUntrustedObject
6. shanghaiMidnightRangeCorrect
7. repeatPaymentKeepsTimestampAndNoChange
8. countAndRowsShareRepeatableReadSnapshot

与原 lifecycle24、statistics4、slot1 合计37项专项实际执行。最初新增时间断言把提交前 JVM 毫秒与 MySQL DATETIME 秒精度相比导致1失败；仅修新增测试，改读第一次已提交的 SQL 时间再比较重复支付，生产代码及保留首次时间要求不变。

旧 P0 迁移回归10项、审计迁移8项实际通过。安全缺失清理接口 RED 不启动危险旧路径；修正后错误 binary/launcher/port/UUID/datadir 都拒绝关闭，正常 SQL SHUTDOWN 后零拥有的子进程，数据保留，原业务断言不变。这不是真实复现历史遗留进程的证明。

两独立临时实例对比“全新schema”和“旧schema→P0→审计升级”：九表、65列、审计11列、14条外键列记录、审计零业务外键；完整列/默认值/排序规则/索引/FK元数据一致。原八表均有虚构行，逐表行数和原列 SHA256 摘要前后一致。旧生命周期夹具只含七个业务表，不冒充这项八表完整迁移证明。

真实原 PaaS/WildFly REST/EJB 请求逐项演示13事件并SQL对账。精确 actor/event/result 临时触发器分别破坏成功审计：科室修改及 book/cancel/start/complete/pay 均返回500，八个原业务表逐行摘要完全不变，只持久化同requestId的独立FAILED/空目标；独立 SQL FOR UPDATE 可重新取得相应业务锁。完成诊疗的预约/病历/账单整笔回滚包含在完整摘要检查内。登录成功审计失败返回500且无令牌；仅破坏FAILED写入时，错误登录仍401/无令牌/无事件，并产生安全 AUDIT_WRITE_FAILED 告警。finally 精确去除触发器，不在原运行库注入故障。

独立 nonce QA WAR 保留55个生产 PaaS class字节，仅新增验证类/配置：

- 实际连接处于 JTA_ACTIVE、JDBC isolation4、MySQL REPEATABLE-READ、autoCommit=false；上海午夜2026-10-08的实际 JDBC epoch为1791388800000。配置属性不代替这个读数。
- QA-only Hibernate SQL准备拦截器在生产 AuditQueryService 计数已完成、列表读取前暂停；原 `/paas/api` 的另一个真实写请求先提交。当前实际 JTA count/list仍为4/4，新请求为5；中点事务Active。生产服务字节未改，这与独立resource-local测试分别报告。
- 生产runner/EJB成功返回后，QA包装显式抛展示异常：HTTP500只留下SUCCESS，没有FAILED。这是QA包装边界，不是原生产REST被原地注入展示异常。
- 八个并发原PaaS请求同时未完成，唯一服务端ID、actor/tenant/object及单事件分别SQL匹配；仅延迟指定验收账号的成功audit插入以确保重叠，finally精确删除测试触发器。这不是50用户性能报告。

## 门户与浏览器证据

真实医院浏览器已观察默认30天/脱敏八列、1280桌面和390小屏：整页无横向溢出，表格自身可滚动；真实键盘ArrowRight产生焦点、可见轮廓及scrollLeft0→40。实际GET表单有空结果、事件+FAILED筛选、size1/第二页，以及统计日期修改后保留独立审计范围/页码。截图是在工具对话中实际观察，不提供不存在的本地图片路径。

真实平台浏览器已观察全局安全事件、三家验收医院目录及可选的停用医院（该院结果明确为空、total0）；专用主医院+CONSULTATION_COMPLETE/FAILED、size1命中对应失败事件。主医院全事件80条，实际下一页为page2/一行；统计GET改为10月8日单日后保留审计医院/日期/page2/size1。1280桌面document宽1265/1265、审计904/1100；390小屏document375/375、审计320/1100，均无整页横向溢出。真实ArrowRight焦点、solid 2.66667px轮廓与scrollLeft0→40已观察。停用医院空结果证明目录可选及空态，不冒充该院有历史记录的浏览器证明。

独立 actual Tomcat `qa-portal` 保留13个生产门户class字节，只设临时 servlet PaaS URL 并加nonce属性存在性观察器。loopback代理只对审计GET分别注入500和9秒延迟，原 PaasClient仍8秒读取超时；实测超时8104ms。科室/医生/号源/订单/上下文/统计各阶段真实透传200，五个其他业务分区与实际JSP锚点保留，审计显示错误而非假的空结果。

审计401及更早的订单401均清零全部私有请求属性、使原认证session失效、无token/role/username并返回实际登录页；两次重新正常登录后恢复真实读取。JSP允许创建新的匿名session，因此观察器检查原session失效和当前无认证，而不把“必须不存在任何session”作为业务契约。更早401的完整字段检查只在QA请求里预置存在性sentinel，不记录字段值。HTTP/JSP通过不等于浏览器故障页已验证。

后续真实浏览器发现独立QA门户未加载样式：文件及归档资产存在且与原门户一致，但QA生成web.xml未保留原`default`→`/assets/*`映射；实际QA的CSS/JS GET为404，原门户各为200。先执行真实资源行为断言记录RED（2执行/2失败），再最小修复ignored QA生成器并仅重部署QA WAR，保留原映射；GREEN两项均200、MIME正确、SHA与原字节一致，13个生产门户class及原门户/PaaS WAR哈希保持不变，旧QA归档与身份保留。这不是生产UI修改，不影响原门户已有布局证据；未据此把原QA无样式页面算作布局通过或真正禁用脚本验证。

修复前的一次真实浏览器500刷新已观察：审计表不存在、非假的空态，显示“诊疗服务暂时不可用，请稍后重试”；科室/医生/排班/账单/统计五区仍存在，tbody行数为10/4/4/4/1，服务端同时证明六路真实透传200与独立审计500。已保存并查看真实截图，但无样式不能冒充布局证明。错误文案与设计中的“审计服务暂时不可用”用词差异保留给独立评审，本次不改生产提示。该段为修复前历史；修复后的正式故障与恢复结果见下文。控制中断时已恢复PASS，修复后再次读回PASS及零触发器。

QA修复后已正常登录，真实1280桌面截图确认样式/绿色侧栏恢复。正式500窗口仅一次刷新：审计错误存在、审计表不存在、非假空态，五业务区行数10/4/4/4/1保留；有样式的真实错误卡片与每日趋势同画面截图已保存并查看。独立路由对账恰七条：六路真实200、审计独立500；已恢复PASS后串行安排超时窗口，不以该500截图替代原门户完整布局/键盘验收。

真实浏览器超时窗口仅一次刷新，耗时8593ms后显示审计错误、无审计表/非假空态，五业务区行数10/4/4/4/1保持。有样式的错误/趋势截图已保存并查看，路由证明六路真实200及审计9000ms延迟，结束后实际恢复PASS。这是浏览器计时，与此前HTTP实测8104ms分别报告，不把截图/工具等待累计时间当作客户端超时。

10月9日跨日恢复后真实QA正常登录已观察，默认日期2026-09-10..2026-10-09。新的401窗口仅一次刷新，实际登录表单/登录标题及“登录状态已失效，请重新登录”提示出现，七个私有分区均不存在；有样式截图已保存并查看，账号密码字段为空。独立路由对账为六路真实200及一次审计401，结束后实际恢复PASS，随后正常登录恢复，结果见下段。此前环境/容量中断的401窗口没有浏览器GET，不追溯记为通过。

401结束并读回PASS后，使用既有虚构医院账号正常表单登录恢复，真实页面为医院门户、无登录表单、七私有分区及20行审计恢复，日期仍为2026-09-10..2026-10-09。有样式恢复截图已保存并查看；未绕过凭据、Cookie或令牌验证。

独立停用历史窗口通过原平台API仅切换专用主医院，校验数据库UUID/端口/datadir、专用医院ID/名称及原ACTIVE状态，代理保持PASS、无故障触发器。医院既有会话仅一次普通刷新、无登录/写动作：仍为医院门户并明确提示“医院已停用：暂停预约、取消、接诊、缴费和资源维护，历史可查”，只读维护移除动作表单，不以“没有disabled按钮”误判失败；20行审计/total85，停用前既有FAILED CONSULTATION_COMPLETE事件关联UUID真实出现在表中，八安全列且tbody无链接。两张实际只读/历史表截图已保存并查看；一次AX名称定位不匹配后改用已观察href定位，滚动不产生额外GET。

观察后原平台API按expectedDISABLED恢复同院ACTIVE，成功事件与SQL状态对账。八个原业务表的完整行摘要与窗口前完全一致，其他医院行不变，旧事件保留、零触发器；两次状态审计记录保留，不抹去事件。恢复后仅一次真实浏览器刷新：仍为医院门户、无登录表单/停用只读提示，科室维护输入框恢复、20行审计及既有事件/恢复状态事件均真实可见，有样式截图已保存并查看。这不是用空的停用医院冒充已有历史，也没有修改演示医院或新建账号。

尚未执行：真正禁用 JavaScript 后提交GET。当前工具没有禁用JavaScript能力，普通GET、静态HTML或CSP不作为替代。后续记录只在实际观察后更新，不将等待人工登录记为通过。

## 自动回归与交付边界

最终 `mvn -f course/pom.xml clean verify` exit0/BUILD SUCCESS：401项发现，364实际执行，37默认跳过，零失败/错误；Node7/7、零跳过。最终独立MySQL专项37/37、零失败/错误/跳过，Pester专项1/1；该实例正常SQLSHUTDOWN后拥有的子进程为0，datadir与证据保留，不把默认跳过算通过。最终重建WAR与已实际测试部署的全部生产/contract class字节一致（PaaS93、Portal51），未热替换浏览器运行中的WAR；归档哈希另列，不将构建时间戳造成的ZIP差异当作新实机部署。

本地凭据、JWT、临床响应正文、备份、QA源码/WAR、原/恢复身份、日志与摘要只保留在 ignored `.superpowers/sdd/2026-10-04-medical-cloud-course-audit/`，不随提交发布。迁移错误路径保留预期access-denied/拒绝诊断；构建也有既有编译/H2/唯一键诊断，不声称输出完全无警告。Task4历史RED顺序例外保留在整体报告，不追溯改写为严格全程TDD。

浏览器观察结束后，关闭前再次核对精确数据库身份、主医院ACTIVE、零触发器、全八表原摘要及原PaaS/门户/修正QA WAR哈希。仅关闭最新精确身份的nonce代理、WildFly管理CLI、Tomcat正常关闭端口及MySQL SQL SHUTDOWN，不强杀或删除文件。运行清理首尝试因局部变量与PowerShell只读`$HOME`大小写冲突，在真正关闭前退出；保留RED，仅改该ignored助手的局部变量名后相同身份GREEN。独立关闭后检查拥有进程0、全部专属监听0、datadir及UUID/证据/归档均保留，三份部署WAR哈希仍相同；已有保护实例和教师安装未操作。此处ACTIVE/零触发器/八表证明来自真实关闭前SQL读数，不冒称离线后重新查询数据库。

MySQL5.6兼容目标、其他教师机器、云端部署、真正禁JS浏览器及全主要流程多浏览器兼容均未实测。既有PowerDesigner模型/图为历史八表版本，不修改、不据此声称当前九表结构完整图已更新。附件、病历更正、新建医院/管理员等其他已约定功能仍是独立缺口；本审计切片不是医疗合规认证或全项目完成。
