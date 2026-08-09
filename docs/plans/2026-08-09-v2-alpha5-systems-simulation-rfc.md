# v2.0 alpha.5 系统课程确定性模拟 RFC

> 制定日期：2026-08-09
> 目标版本：`2.0.0-alpha.5`

## 1. 目标

Alpha5 为计算机系统、操作系统和计算机网络建立一个可复用的离线模拟活动类型。它不是通用仿真引擎，而是面向教学的有限状态实验：课程定义状态、动作、确定性检查点与目标状态，学生通过操作序列形成可复核 artifact，Java 评价器输出稳定原因码和证据。

首批纵向切片为：

- 计算机系统：取指、译码、执行的指令周期；
- 操作系统：最短作业优先的选择、调度和完成过程；
- 计算机网络：封装、下一跳解析、转发与交付过程。

## 2. 选择的模型

所有模拟共享以下领域模型：

```text
SimulationActivitySpecification
  -> states
  -> actions(from, to, observation)
  -> checkpoints(requiredState, reasonCode)
  -> goalState

SimulationActivityArtifact
  -> ordered actionIds

SimulationActivityEvaluator
  -> replay
  -> criteria/checkpoints
  -> stable reason code
  -> activity-evidence-v2
```

采用有向状态图而不是分别实现 CPU、调度器和网络专用 UI，可以把领域内容留在课程定义中，并让新增课程复用同一套运行、评价和证据链。规格加载时必须验证引用、唯一性与从初始状态到目标/检查点的可达性；运行时未知动作和非法转移均确定性拒绝。

## 3. UI 与运行边界

- JavaFX 工作区展示当前状态、动作、观察记录、历史和检查点；
- 推荐动作只负责引导，不替代评价；撤销和重置通过重放 artifact 得出状态；
- 状态变化使用现有动效基础设施，并服从“减少动态效果”设置；
- 权威评价只在应用层 `SimulationActivityEvaluator` 中完成；
- 模拟完全离线，不调用 AI、Cloud、本机编译器或外网；
- SQL 风险规则、本地代码 Runner 和安全评价边界不因模拟活动改变。

## 4. 数据与兼容性

schema 14 仅追加三门内置课程及其章节、成果、知识点、活动和关联，不删除或改写用户学习数据。旧库按既有迁移链升级；活动 JSON 继续保存于通用活动表，类型为 `SIMULATION`。

## 5. 验收条件

- 三类课程各至少一个真实可操作实验；
- 正确动作序列达到全部检查点并通过；
- 未知动作、乱序动作、未达到检查点与未达到目标均给出稳定原因码；
- 课程可在无网络、无 AI、无外部工具链条件下完成；
- 课程地图、实验工作区、评价结果、事件证据和重启后的持久化路径贯通；
- 完整 Maven 测试与文档链接检查通过。
