# 医疗云实验 1.2 / 1.3：PowerDesigner 绘图说明

本实验交付的是 **PowerDesigner 可编辑的企业架构模型**，不是 70 台服务器的部署。不要把图中的 Windows、i5 配置和网络节点写成已实际启动或实测的资源。

## 文件

- [`medical-cloud-iaas-generated.eam`](medical-cloud-iaas-generated.eam)：最终原生 EAM，内有两张 Technology Infrastructure Diagram。
- [`medical-cloud-lab-visual-reference.png`](medical-cloud-lab-visual-reference.png)：生图得到的整体布局参考。图片仅用于版式；准确文字、70 个编号和连线以 EAM 为准。
- [`generate-medical-cloud-iaas.vbs`](generate-medical-cloud-iaas.vbs)：PowerDesigner 16.5 原生 OLE 自动化生成脚本。
- [`validate-medical-cloud-iaas.ps1`](validate-medical-cloud-iaas.ps1)：对原生 EAM 的结构和实验字段做自动核对。

已有的 `medical-cloud-course.pdm` 是数据库模型，不能代替本实验的服务器集群图；生成脚本不会修改它。

## 最快做法：打开脚本生成的模型

在 PowerDesigner 中使用 **File → Open Model or Project** 打开 `medical-cloud-iaas-generated.eam`。对象浏览器应有两个图：

1. `Medical Cloud Application Scenario`：六层场景图，以及浏览器/患者等角色经过 Internet、Tomcat 9 `/portal`、WildFly 10 `/paas/api`、MySQL 的课程实现链。监管角色明确标注为 planned。
2. `IaaS Server Cluster - 70 Logical Servers`：7 个纯排版分组，每组 10 个服务器对象；`IaaS-01` 到 `IaaS-70`，每台连至组内网络节点，7 个组节点再连至核心 IPv4/IPv6 网络节点。图中的 70 台均未实际运行。

如需重新生成，在仓库根目录执行：

```powershell
& $env:SystemRoot\System32\cscript.exe //nologo docs\course-architecture\generate-medical-cloud-iaas.vbs 'docs\course-architecture\medical-cloud-iaas-rebuild.eam'
& docs\course-architecture\validate-medical-cloud-iaas.ps1 -ModelPath 'docs\course-architecture\medical-cloud-iaas-rebuild.eam'
```

脚本使用本机 `D:\Sybase\PowerDesigner 16\ShellNew\PDModel16.eam` 作为空白 EAM 模板。如果 PowerDesigner 安装位置不同，修改脚本中的 `templatePath`。脚本拒绝覆盖已存在的输出文件；重建时换一个新文件名。由于 PowerDesigner 16.5 的 OLE `OpenModel` 对此工作区的中文路径报错，脚本先在系统临时目录用纯 ASCII 路径构建并关闭模型，再复制到指定位置；不会启动实体机或虚拟机。

## 想自己照图绘制时

1. **建立模型**：File → New Model；类别选 Technology，项目选 Technology Diagram，名称设为医疗云架构。PowerDesigner 16.5 实际创建的图类型会显示为 Technology Infrastructure Diagram；将模型另存为新的 `.eam`，不要覆盖 `.pdm`。
2. **场景图**：用 Architecture Area 从下到上画基础设施、虚拟化、IaaS/PaaS/SaaS、医疗云服务环境、Internet、用户六层。基础设施内写高性能服务器、高速通信网络、大规模存储、动态分配和安全机房；虚拟化写计算/存储/网络资源池；平台层分别写 IaaS、PaaS、SaaS。
3. **项目个性化**：SaaS 写 Tomcat 9 `/portal`；PaaS 写 WildFly 10 `/paas/api`、EJB/JPA 与 JNDI；数据服务写 MySQL。服务组件写预约、接诊、病历和模拟订单；接口写门户与 HTTP/JSON。用户依次写患者、医生/医院业务人员、管理机构人员（规划示意）、接口开发人员。用 Link 画浏览器/用户 → Internet → Tomcat → WildFly → MySQL 的主链。
4. **集群图**：在同一 EAM 模型节点上新建第二张 Technology Infrastructure Diagram。用 Architecture Area 摆 7 个布局组，每组放 10 个 Hardware Server，分别命名 `IaaS-01`…`IaaS-70`；放 7 个组内 Network Node 和 1 个核心 Network Node，服务器连组节点、组节点连核心节点。组号只是排版，不代表真实机房或可用区。
5. **统一配置**：每台服务器属性中的 Comment 写 `Intel i5 3.2 GHz; course expression 3.2*2^10*2^10 operations (not benchmarked); 4 GB memory; 100 GB storage; IPv4 and IPv6; Windows OS; antivirus; firewall; intrusion prevention; secure machine room`。课程给出的运算表达式原样保留，但不能当成实际性能测试值。在图上再放一块通用配置说明，不必把长配置重复画进每个小图标。图上写“设计模型，70 台逻辑/虚拟服务器，非实际运行结果”。
6. **报告截图/导出前检查**：分别打开两张图，使用 PowerDesigner 的全局视图检查有无裁切，再放大检查文字。对象浏览器应有且仅有 `IaaS-01`…`IaaS-70`；截图或图像导出时不要把数据库 PDM 页面误当作集群图。报告中称“建模结果”，不要称“70 台服务器运行结果”。

## 参考图生成提示词

使用内置生图功能生成，类型为 `infographic-diagram`：16:9 白底、细深蓝边线与浅蓝分层面板的课堂技术架构示意图；左侧自下而上六层（Foundation、Virtualization、IaaS / PaaS / SaaS、Medical Cloud Services、Internet、Users），顶层四类角色图标；右侧七个分组，每组十个服务器图标并连接核心网络，配 IPv4/IPv6 与安全盾牌。要求矩形、清晰层级、无品牌或真实部署宣称。图片不是最终技术模型。
