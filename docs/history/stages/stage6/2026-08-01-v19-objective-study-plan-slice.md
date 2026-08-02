# v1.9 课程目标与确定性学习计划首个纵向闭环

> 后续状态：本记录的“当前边界”已由完整功能收敛实现覆盖，最终结果见
> `docs/history/stages/stage6/2026-08-01-v19-teaching-orchestration-implementation.md`。

> 实施日期：2026-08-01  
> 本地数据库版本：app schema 10  
> 云端数据库版本：Cloud schema 5  
> 计划策略版本：`v1.9.0-r1`

## 已完成

- 新增课程目标、同课程先修关系、目标资源关联和学习计划应用层契约。
- 课程目标可关联现有知识点、已发布知识文章或启用中的共享练习版本。
- Cloud 新增 `/api/v1/v19` 目标与学习计划接口；教师/管理员负责写入，非所有者只能读取启用课程的启用目标及可见资源。
- 先修边写入前校验同课程、启用状态和环路；跨课程、失效资源及环路会被拒绝。
- 学习计划完全由 Java 确定性策略生成，不调用 AI、Embedding、Qdrant 或联网搜索；相同身份、课程、事实和策略版本产生稳定动作 ID 与排序。
- 首版计划最多 7 条，优先补齐未满足的先修目标；没有学习证据时使用 `INSUFFICIENT_EVIDENCE`，先修阻塞使用 `PREREQUISITE_GAP`。
- app schema 10 提供目标缓存、计划快照、动作、证据、Outbox 和辅导反馈的后续持久化骨架；Cloud schema 5 提供目标图及后续同步/教学编排骨架。

## 验证

- 聚焦测试：`mvn -q "-Dtest=DeterministicStudyPlanServiceTest,SqliteAppDatabaseInitializerTest,SqliteSchemaMigratorTest,V14CloudApiClientIntegrationTest" test`，通过。
- 全量测试：`mvn -q test`，328 项通过、0 失败、0 错误；2 项真实 AI 环境测试按设计跳过。
- HTTP 集成覆盖教师创建两个目标、绑定知识/练习、添加先修关系、拒绝反向环路，以及学生取得带 `PREREQUISITE_GAP` 的稳定计划。
- 迁移覆盖 app 1→10、重复迁移和未来版本拒绝；Cloud 服务启动后最大 schema 版本为 5。

## 当前边界与下一步

- 当前是最小纵向闭环，尚未接入本地学习事件证据、动作生命周期、桌面工作台和跨设备状态同步。
- 非课程所有者的课程可见性目前沿用 v1.4 的启用课程读取边界；班级成员关系收紧将在教师/学生闭环阶段与课程授权模型一起完成。
- schema 中的计划快照、证据、Outbox、教学周期和辅导反馈表为后续阶段保留，不代表对应功能已交付。
- 下一阶段先把本地真实学习事件归一化为可追溯证据，再实现计划重算、动作状态和 10,000 事件性能夹具。

本次不修改 `pom.xml` 版本、不打 `v1.9.0` 标签、不生成发布物，也不部署生产服务。
