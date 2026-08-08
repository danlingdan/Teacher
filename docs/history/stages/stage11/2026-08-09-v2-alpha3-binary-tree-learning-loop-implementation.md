# v2.0 alpha.3 二叉树学习闭环实施记录

> 实施日期：2026-08-09
> 版本：`2.0.0-alpha.3`

## 已完成

- 扩展 sealed 活动规格和 artifact，真实开放 `QUIZ` 与 `TRACE`；
- 新增测验和步骤跟踪确定性评价器及稳定原因码；
- schema 12 发布二叉树课程、知识点关系、测验和六节点前序跟踪活动；
- 通用活动服务持久化会话、评价摘要、artifact 哈希和结构化学习事件；
- 实验工作区按类型切换 SQL Runner、测验或二叉树可视化，不阻塞 JavaFX 线程；
- 诊断策略合并 SQL/QUIZ/TRACE 证据，首页 `RETRY_ACTIVITY` 可回到非 SQL 活动；
- 教师/管理员复核和发布反馈经过应用身份校验，学生读取按 owner 隔离；
- 修复课程地图直达活动被异步目录默认选择覆盖的竞态。

## 安全与边界

本阶段没有代码执行器。QUIZ/TRACE 评价器不接收 JDBC 连接、不访问网络、不调用模型，也不改变
SQL 风险分析、确认和只读执行链。评价存储保留结构化结果和 artifact SHA-256，不保存无关敏感正文。
Cloud 2.0 通用活动同步、任意代码 Runner 和系统模拟实验仍属于后续 Alpha。

## 主要实现位置

- `domain/activity/`：QUIZ/TRACE 规格、题目、树节点与 artifact；
- `application/activity/`：评价器、学习提交和教师复核合同；
- `infrastructure/database/JdbcActivityLearningService.java`：会话、评价、事件与学生反馈读取；
- `infrastructure/database/JdbcActivityReviewService.java`：教师权限复核与反馈发布；
- `infrastructure/database/SqliteSchemaMigrator.java`：schema 12 与内置课程；
- `desktop/component/ActivityInteractionPane.java`：测验和二叉树交互表面；
- `infrastructure/database/JdbcLearningDiagnosisService.java`：跨活动诊断与推荐。

验证证据见 [alpha.3 阶段门禁](../../../acceptance/2026-08-09-v2-alpha3-stage-gate.md)。
