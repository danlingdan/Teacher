# v2.0 alpha.1 通用活动内核实施记录

## 交付范围

本阶段完成 v2.0 总计划的 alpha.1：通用活动/课程图谱/评价/事件和迁移骨架，以及现有 SQL
练习的真实兼容适配。UI 2.0、非 SQL 活动实现和 Runner 没有提前进入本阶段。

## 关键实现

- `domain.activity`：活动类型、定义、会话、sealed 规格与产物；
- `domain.course`：课程、章节、成果、知识点和关系；
- `application.activity`：评价合同、分发器、SQL 适配器及旧接口桥接；
- `application.learning.ActivityMasteryEvidence`：带活动/评价器/证据版本的可重算证据骨架；
- `SqliteSchemaMigrator` schema 11：通用表、旧数据回填与 SQL 投影同步 trigger；
- `DefaultLearningEventService` / `JdbcLearningEventRecorder`：通用活动元数据进入结构化事件列；
- Spring 运行时将旧练习服务接到通用分发器，再委托原确定性 SQL 评测器。

SQL 安全边界未改变：模型和通用活动层没有 JDBC 连接；多语句/非只读 SQL 仍由原共享服务拒绝，执行仍有查询只读、超时和结果上限。

## 验证

完整命令和最终结果记录在 [alpha.1 门禁](../../../acceptance/2026-08-09-v2-alpha1-stage-gate.md)。
最终 `mvn test` 为 425 项、0 failure、0 error、2 项按既有条件跳过；Windows EXE、ZIP、
app-image、两项校验和和 SBOM 生成成功，Windows package version 为 `2.0.1001`。
本次未提交、未打标签、未推送、未部署或发布 GitHub Release。
