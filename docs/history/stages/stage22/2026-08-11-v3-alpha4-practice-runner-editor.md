# v3.0 Alpha.4 练习、实验与编辑器实施记录

> 日期：2026-08-11
> 阶段代码版本：`3.0.0-alpha.4`；批次最终代码版本为 `3.0.0-alpha.5`

## 完成范围

- SQL 练习完成“选题、预览、确认、作答、反馈”主路径，确认前不创建 Monaco 模型。
- 练习运行与提交复用 Java `ExercisePracticeService`，确定性评价和学习状态不迁入前端。
- Java/Python/C/C++ 实验接入真实 `LocalCodeRunner`，显示能力探测、编译/运行结果、原因码、资源使用和进度。
- Monaco 增加多语言模型、结构上下文补全、内容上限诊断、运行/提交快捷键；请求可由显式 ID 取消。

## 边界

本地 Runner 的资源、输出、超时与进程树清理由现有 Java 实现强制；前端限制只是提前反馈。JavaFX 回退保留，本阶段不改变 Runner 威胁模型。

批次门禁见 [Alpha.3 至 Alpha.5 本地阶段门禁](../../../acceptance/2026-08-11-v3-alpha3-alpha5-stage-gate.md)。
