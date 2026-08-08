# SQLTeacher v2.0 alpha.1 通用活动内核 RFC

> 状态：已实现
> 适用版本：`2.0.0-alpha.1`
> 总计划：[v2.0 计算机专业学习平台总计划](2026-08-02-v2.0-computer-science-learning-platform-plan.md)

## 决策

alpha.1 采用“公共活动定义 + sealed 活动规格/产物 + 类型化评价器”的结构，不在旧
`ExerciseDefinition` 上追加其他课程活动的可空字段。首个规格为 `SQL`；其余七种类型先冻结稳定枚举，只有注册了匹配评价器后才能执行。

核心契约如下：

- `LearningActivityDefinition` 保存公共元数据、课程/章节、知识点、难度、版本和类型化规格；
- `ActivitySpecification` 与 `ActivityArtifact` 为 sealed 接口，alpha.1 只允许 SQL 实现；
- `ActivityEvaluator` 输出带稳定原因码、评价器版本、证据版本和资源摘要的
  `ActivityEvaluationResult`；
- `DefaultActivityEvaluationDispatcher` 按活动类型注册评价器，未注册类型或不匹配产物以稳定错误码拒绝；
- `ActivityMasteryEvidence` 是后续跨活动诊断的输入骨架，权威诊断仍由确定性策略重算；
- 课程图谱使用课程、章节、学习成果、知识点和三类知识关系的显式领域对象。

## SQL 兼容策略

`SqlLearningActivityAdapter` 将现有 SQL 题目和数据集映射为 `SQL` 活动；
`ActivityBackedSqlExerciseEvaluationService` 保留旧 `SqlExerciseEvaluationService` 合同，因此桌面练习服务无需改写。实际评价仍委托给原
`DeterministicSqlExerciseEvaluationService`，继续执行单条只读 `SELECT`、风险分析、SQLite
`query_only`、10 秒超时和 5,000 行上限。通用层不接收 JDBC 连接，也不能跳过该服务。

## 数据与迁移

本地 schema 由 10 追加到 11：

- 新增课程、章节、学习成果、知识点关系、活动定义、通用会话和评价结果表；
- 为学习事件追加活动 ID/类型、评价器版本、证据版本和原因码列；
- 将已有 SQL 题目和活动会话确定性回填到通用表；
- 通过 SQLite trigger 保持后续 SQL 题目/会话与通用投影同步；
- 数据库约束拒绝未知活动类型，应用分发器拒绝没有评价器的活动；
- 掌握快照仍是可删除、可重算派生数据，迁移不修改旧尝试或学习事件事实。

## 预发布版本映射

Maven、标签和公开资产使用 SemVer `2.0.0-alpha.1`。WiX/MSI 只能使用数值版本，打包脚本采用单调映射：

| 阶段 | Windows package version |
| --- | --- |
| `alpha.N` | `major.minor.(1000 + N)` |
| `beta.N` | `major.minor.(2000 + N)` |
| `rc.N` | `major.minor.(3000 + N)` |
| GA/补丁 | `major.minor.(4000 + patch)` |

因此 alpha.1 的 Windows package version 是 `2.0.1001`，文件名和应用内版本仍是
`2.0.0-alpha.1`。发布工作流将预发布标为 prerelease 且不设为 Latest；本次实现没有创建标签或发布 Release。
