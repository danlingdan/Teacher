# A 第三轮 AI 安全编排记录

## 1. 完成范围

本记录对应 2026-07-21 至 2026-07-24 计划中成员 A 的任务：让 AI 结果进入
Java 应用层风险检测，并确保生成的 SQL 不会被直接执行。

本轮新增 `Nl2SqlSafetyService` 和默认实现 `DefaultNl2SqlSafetyService`。统一调用顺序为：

```text
自然语言请求
-> Nl2SqlService 生成并校验结构化草稿
-> SqlRiskAnalysisService 执行 Java 风险检测
-> Nl2SqlSafetyResult 返回草稿和风险结论
-> 非只读或多语句草稿记录 SQL_RISK_BLOCKED
-> UI 仅预览或复制草稿，不自动执行
```

编排服务不依赖 `SqlExecutionService`，因此不存在从 AI 结果直接进入 JDBC 执行的路径。

## 2. 接口契约

调用方使用：

```java
Nl2SqlSafetyResult result = nl2SqlSafetyService.generateAndAssess(request);
```

`Nl2SqlSafetyResult` 同时保留：

- `plan`：AI SQL 草稿、意图、解释、模型和 Prompt 版本。
- `riskAnalysis`：风险等级、是否可执行、是否要求确认、是否多语句、语句类型和原因。
- `accepted()`：仅当草稿是单条、可执行、无需确认的 `SELECT` 时返回 `true`。

`accepted()` 只表示草稿通过 AI 只读安全门，不代表执行授权。后续 UI 仍必须将 SQL
展示为草稿；用户复制到 SQL 练习页后，实际执行继续经过统一 SQL 执行安全链路。

## 3. 适配边界与迁移

- C 的 `Nl2SqlService` 只负责 Provider 调用、JSON 解析和结构校验。即使模型违反
  Prompt 返回 `UPDATE` 或多语句，也保留草稿交给 Java 风险服务判定，而不是在基础设施层
  清空草稿。
- B 的 `SqlRiskAnalysisService` 是唯一权威风险结论，AI 流程不复制 SQL 分类规则。
- D 的 AI 页面必须注入 `Nl2SqlSafetyService`，不得直接使用 `Nl2SqlService`，也不得在生成
  回调中调用 `SqlExecutionService`。
- 风险草稿只记录语句类型、风险等级和是否多语句，不把 SQL 原文写入学习事件。
- Provider 不可用、JSON 非法或结构校验失败时，返回无草稿结果；应用不崩溃，也不伪造
  SQL 风险拦截事件。

## 4. 验证范围

自动化测试覆盖：

1. 单条 `SELECT` 草稿通过安全门且不产生风险拦截事件。
2. `UPDATE` 草稿被保留并标记为不接受，同时记录风险拦截事件。
3. 多语句草稿被标记为不接受。
4. 无草稿降级结果不产生伪风险事件。
5. Spring 真实配置和 Mock 配置均提供统一安全编排服务。
6. SQLite 联调验证 AI 返回 `UPDATE` 后学生成绩保持不变。

验证命令：

```powershell
mvn test
```
