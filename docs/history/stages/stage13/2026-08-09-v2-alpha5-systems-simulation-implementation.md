# v2.0 alpha.5 系统课程确定性模拟实施记录

> 实施日期：2026-08-09
> 版本：`2.0.0-alpha.5`

## 已完成

- 新增 `SIMULATION` 规格、artifact、状态、动作和检查点领域模型；
- 新增纯 Java 确定性评价器，通过重放动作序列生成逐项 criteria、稳定原因码和 `activity-evidence-v2` 证据；
- schema 14 增加计算机系统、操作系统和计算机网络三门内置课程；
- 首批实验覆盖指令周期、最短作业优先调度和分组交付；
- 通用实验工作区增加状态、观察、动作、历史、检查点、撤销、重置和提交交互；
- 状态反馈动效复用 UI 2.0 动效策略，并服从减少动态效果设置；
- 模拟接入课程地图、活动提交、评价结果和学习事件持久化链路；
- 增加规格校验、正确/错误动作序列、schema 升级、三课程加载与离线提交测试。
- 完成 455 项 Maven 测试、Windows EXE/ZIP/app-image、校验和、44 组件 SBOM、敏感内容扫描与隔离数据目录启动冒烟。

## 设计结论

三类课程使用同一个有限状态模拟内核，课程差异保存在数据定义中。该边界避免把 CPU、操作系统和网络规则写进 JavaFX 控制器，也为后续协议状态机、缓存一致性、并发调度等课程保留扩展路径。

UI 中的当前状态和推荐动作是即时反馈；权威结果由应用层评价器重新播放完整 artifact 得出。未知动作、非法状态转移、未完成检查点和未到达目标均不能由界面提示绕过。

## 数据与安全边界

- schema 14 只追加内置课程内容，不覆盖已有课程或学习数据；
- 模拟不调用网络、AI、编译器、Shell、SQL 或本地代码 Runner；
- 本次没有改变学生代码本地运行、SQL 风险确认、Cloud 权限或隐私策略；
- 活动定义在加载时验证引用、唯一性和可达性，损坏内容不会进入运行阶段。

## 主要实现位置

- `domain/activity/Simulation*.java`：状态图规格和 artifact；
- `application/activity/SimulationActivityEvaluator.java`：重放、criteria、原因码与证据；
- `desktop/component/ActivityInteractionPane.java`：通用模拟工作区；
- `infrastructure/database/SqliteSchemaMigrator.java`：schema 14 与三门课程；
- `resources/css/components/pages.css`：模拟状态与检查点样式。

验证证据见 [alpha.5 阶段门禁](../../../acceptance/2026-08-09-v2-alpha5-stage-gate.md)，使用方法见 [离线系统模拟实验指南](../../../guide/16-offline-simulation-labs.md)。
