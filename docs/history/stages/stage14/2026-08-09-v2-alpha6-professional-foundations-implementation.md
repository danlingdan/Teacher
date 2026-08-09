# v2.0 alpha.6 专业基础课程单元实施记录

> 实施日期：2026-08-09
> 版本：`2.0.0-alpha.6`

## 已完成

- schema 15 新增软件工程、程序设计语言与编译、离散数学、AI、安全基础五门内置课程；
- 每门课程均包含章节、学习成果、知识点、活动定义和知识关联；
- 五个活动复用通用模拟工作区和确定性评价器，形成课程专用检查点与稳定原因码；
- 模拟 UI 资源键由阶段性的 `alpha5.*` 收敛为可复用的 `simulation.*`；
- 课程地图现有 11 门课程、34 个活动；八门课程以纯离线模拟为活动路径；
- 五个新单元的空 artifact 与完整 artifact 均通过持久化回归，覆盖失败原因和成功证据。
- 完成 455 项 Maven 测试、Windows EXE/ZIP/app-image、双项校验和、44 组件 SBOM、敏感内容扫描和 schema 15 隔离启动冒烟。
- 增加 `fast` 测试 profile 和 `integration`/`runner`/`live` 标签；热态日常套件为 415 项、9.055 秒，默认完整门禁仍为 455 项。

## 边界结论

Alpha6 没有新增活动类型或外部运行能力。软件工程和编译单元只处理固定教学数据；数学单元只评价结构步骤；AI 单元不调用模型；安全单元只模拟防御门禁。因此本阶段没有引入公网攻击、主机逃逸、凭据访问或模型权威判分路径。

主要内容位于 `SqliteSchemaMigrator` 的 schema 15；交互继续由 `ActivityInteractionPane` 通用模拟工作区承载；权威结果继续由 `SimulationActivityEvaluator` 生成。

验证见 [alpha.6 阶段门禁](../../../acceptance/2026-08-09-v2-alpha6-stage-gate.md)，使用方法见 [专业基础离线实验指南](../../../guide/17-professional-foundation-labs.md)。
