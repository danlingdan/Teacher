# SQLTeacher v2.0 alpha.3 二叉树学习闭环 RFC

> 状态：已实现
> 适用版本：`2.0.0-alpha.3`
> 总计划：[v2.0 计算机专业学习平台总计划](2026-08-02-v2.0-computer-science-learning-platform-plan.md)

## 1. 目标

alpha.3 以“二叉树前序遍历”为第一个非 SQL 纵向课程切片，证明通用活动内核可以承载
学习内容、交互产物、确定性评价、诊断、推荐和教师反馈，而不把 SQL 或 JavaFX 类型泄漏到领域合同。

本切片包含两个真实活动：

- `QUIZ`：判断前序遍历“根 → 左 → 右”的访问规则；
- `TRACE`：在六节点二叉树上依次点击节点，构造并检查完整访问序列。

`SIMULATION`、代码活动和独立 Runner 不在本阶段提前开放，分别保留给 alpha.4 和 alpha.5。

## 2. 类型化活动与评价

- `QuizActivitySpecification` / `QuizActivityArtifact` 保存题目、选项、通过线和学生选择；
- `TraceActivitySpecification` / `TraceActivityArtifact` 保存树节点、遍历类型、期望顺序和访问序列；
- `QuizActivityEvaluator` 与 `TraceActivityEvaluator` 只执行确定性比较，不访问 JDBC、网络、AI 或文件系统；
- 评价结果使用稳定原因码，例如 `QUIZ_BELOW_PASS_SCORE`、`TRACE_INCOMPLETE` 和
  `TRACE_ORDER_INCORRECT`；
- 未注册类型和类型不匹配的 artifact 继续由通用分发器安全拒绝。

## 3. 数据、诊断与反馈

本地 schema 从 11 追加到 12：

- 发布内置“数据结构与算法 / 二叉树遍历”课程、知识点、关系和两个活动定义；
- 复用 `activity_session`、`activity_evaluation_result` 和结构化学习事件保存学生证据；
- 新增 `activity_feedback` 保存教师/管理员发布的活动反馈；
- 跨活动诊断按知识点合并 SQL、QUIZ 和 TRACE 证据，策略版本升级为
  `v2.0.0-alpha3-r1`；
- 非 SQL 薄弱项生成 `RETRY_ACTIVITY` 建议，首页可直接回到对应活动。

评价表保存 artifact 哈希而非学生原始交互全文。教师复核必须携带教师或管理员
`DesktopAccessProfile`；学生只能读取属于当前 owner 的已发布反馈。

## 4. UI 与线程边界

通用实验工作区根据活动类型切换真实 Runner：SQL 继续挂载原工作台，QUIZ 和 TRACE 使用
`ActivityInteractionPane`。数据库读取、评价持久化、诊断和反馈均在后台执行；JavaFX 线程只渲染状态。
二叉树节点带键盘可访问名称和轻量点击动画，“减少动态效果”开启时不播放节点动画。

从课程地图打开活动时，工作区保存待选 activity ID，目录异步完成后不会再被“默认第一项”覆盖。

## 5. 退出标准

- QUIZ 与 TRACE 正确/错误 artifact 产生稳定且可测试的结果；
- schema 11 可升级到 12，空库、重复启动、失败回滚和未来版本拒绝继续通过；
- 学生可从课程地图完成两个活动，评价结果和事件真实落库；
- 同一知识点的跨活动证据形成诊断和 `RETRY_ACTIVITY` 建议；
- 教师/管理员能复核最近评价并发布反馈，学生重新打开活动可见；
- 原 SQL 安全执行链、Cloud API 和权限边界不变。

验证结果见 [alpha.3 阶段门禁](../acceptance/2026-08-09-v2-alpha3-stage-gate.md)。

返回 [迭代计划索引](README.md)。
