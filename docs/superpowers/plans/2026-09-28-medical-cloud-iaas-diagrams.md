# Medical Cloud IaaS Diagrams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PowerDesigner 16.5 中交付课程实验 1.2/1.3 的医疗云应用场景图及 70 台 IaaS 服务器集群图。

**Architecture:** 单独新建 EAM，内含 Technology Diagram 和 Technology Infrastructure Diagram。服务器为 70 个编号唯一的逻辑模型对象，按 7×10 排版、通过网络汇聚节点连通；图像从原生模型导出，不替代模型。

**Tech Stack:** PowerDesigner 16.5 Enterprise Architecture Model；Windows 桌面 UI；PNG 导出。

**Spec:** `docs/superpowers/specs/2026-09-28-medical-cloud-iaas-diagrams-design.md`

## Global Constraints

- 不启动、创建或配置实际实体机/虚拟机，所有配置仅为课程建模属性。
- 不覆盖 `docs/course-architecture/medical-cloud-course.pdm` 或本工作树现有未提交修改。
- 课程实现链准确标注为 Tomcat 9 `/portal` → WildFly 10 `/paas/api` → JPA/JNDI → MySQL；监管统计标“规划/示意”。
- 70 个对象连续命名为 `IaaS-01`…`IaaS-70`；7 组仅为排版，不暗示真实机房或可用区。
- 图内写明“设计模型，非实际运行结果”；实验要求的 i5 3.2 GHz、4 GB、100 GB、IPv4/IPv6、Windows、防病毒、防火墙/反入侵均可查。

## Review Focus

1. 编号缺失或重复：最终逐个核对 01–70，数量必须恰为 70。
2. 图层混淆：场景图必须分清基础设施、虚拟化、IaaS/PaaS/SaaS、服务环境、因特网和四类用户。
3. 运行状态误述：模型与报告不得写成 70 台已部署，监管功能不得写成已实现。
4. 网络歧义：全部 7 组经汇聚节点连通，但不画成每两台机器直接相连。
5. 导出不可读/模型损坏：重新打开 `.eam`，放大检查标签，PNG 不裁切关键内容。

---

## Files and ownership

- Create: `docs/course-architecture/medical-cloud-iaas.eam` — 原生可编辑模型，承载两张图。
- Create: `docs/course-architecture/medical-cloud-application-scenario.png` — 图 1 的报告插图。
- Create: `docs/course-architecture/medical-cloud-iaas-cluster.png` — 图 2 的报告插图。
- Modify: `docs/course-architecture/README.md` — 只新增实验图入口、范围及“非实机运行”说明；不改已有 PDM 说明和开发事实。

### Task 1: 建立 EAM 与医疗云应用场景图

**Files:** Create `.eam`; later export `medical-cloud-application-scenario.png`.

**Interface:** 在 PowerDesigner 中，新建 Enterprise Architecture Model，选择 Technology Diagram；图名“智慧医疗云服务工程应用场景”。该 EAM 是 Task 2 的唯一模型容器。

- [ ] **Step 1: 新建并另存模型** — 以独立文件 `docs/course-architecture/medical-cloud-iaas.eam` 保存；确认扩展名和 PowerDesigner 对象浏览器中的模型类型。
- [ ] **Step 2: 绘制六层与角色** — 按 spec 的顺序布置基础设施、虚拟化、IaaS/PaaS/SaaS、医疗云服务环境、因特网及四类用户，使用 Architecture Area、组件与 Link；监管用户加“规划/示意”。
- [ ] **Step 3: 标注课程实现链** — Tomcat 9、WildFly 10、JPA/JNDI、MySQL 及门户/API 接口顺序可读；MySQL 不画成 70 节点实际部署。
- [ ] **Step 4: 检查并保存** — 逐项核对 Review Focus 第 2、3 条；PowerDesigner 关闭并重开模型后图仍可编辑。

### Task 2: 建立 70 节点 IaaS 集群图

**Files:** Modify `.eam`; later export `medical-cloud-iaas-cluster.png`.

**Interface:** 在 Task 1 的 EAM 内新建 Technology Infrastructure Diagram，图名“IaaS 服务器集群（70 台逻辑服务器）”。

- [ ] **Step 1: 建立模板与网络** — 绘制服务器对象、网络/汇聚节点和统一配置图例；模板列出 i5 3.2 GHz、4 GB、100 GB、IPv4/IPv6、Windows、反病毒、防火墙/反入侵、安全机房。
- [ ] **Step 2: 完成 7×10 排版** — 建立 70 个独立服务器对象并赋唯一名称 `IaaS-01`…`IaaS-70`；组名只作布局标签。
- [ ] **Step 3: 表达集群互联与资源能力** — 每组连接到网络节点，网络节点相互连通；标注分布式计算/存储与动态分配为设计能力，不声称实测。
- [ ] **Step 4: 检查并保存** — 核对对象浏览器中 70 个编号和图面连通性、配置图例、非运行声明；关闭并重开 `.eam` 验证保存。

### Task 3: 导出、核验与交付说明

**Files:** Create two PNGs; modify `docs/course-architecture/README.md`.

- [ ] **Step 1: 从 PowerDesigner 分别导出 PNG** — 使用图窗口导出功能，不用截图冒充模型导出；文件名按 Files and ownership。
- [ ] **Step 2: 视觉 QA** — 查看两张完整图，确保重要字样可读、无裁切，必要时调整布局后重新导出。
- [ ] **Step 3: 补充 README** — 链接 `.eam` 与两个 PNG，说明这属于课程实验 1.2/1.3 的设计模型，非 70 台服务器实际部署。
- [ ] **Step 4: 最终验证** — 检查三文件存在、`.eam` 可打开且含两图、编号 01–70 唯一、Git diff 仅包含本任务文件；仅在核验通过后报告完成。
