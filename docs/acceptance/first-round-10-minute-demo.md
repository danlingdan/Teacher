# 第一轮 10 分钟演示脚本

> 本脚本用于 2026-07-14 第一轮独立实现验收，不等同于 7 月 30 日最终演示脚本。
> 当前 JavaFX SQL 页面仍使用明确命名的 Mock 服务；真实 UI 到 SQLite 的接入安排在 7 月 15 日联调。

## 0:00-1:00 说明范围

- 展示第一轮目标：应用契约、SQLite/SQL 安全、Ollama/NL2SQL、JavaFX SQL 页面。
- 明确尚未演示事件记录、AI 助手页面和真实 UI 数据库联调。

## 1:00-3:00 CLI 环境验证

运行：

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

确认：

- Spring Context 可启动。
- `app.db` 和 `demo.db` 可初始化。
- Ollama 可用时展示模型状态；不可用时以 WARNING 降级，不导致程序崩溃。

## 3:00-6:00 JavaFX SQL 页面

运行：

```powershell
mvn javafx:run
```

演示：

- 主窗口和 SQL 练习页。
- 示例 SQL 填充。
- loading、结果、空结果和错误状态。
- 说明当前页面使用 `SqlExecutionMockService`，不宣称已完成真实 UI 数据库联调。

## 6:00-8:00 真实 SQLite 和 SQL 安全证据

运行：

```powershell
mvn -q "-Dtest=JdbcSqlExecutionServiceTest,SqlRiskRegressionTest" test
```

说明测试覆盖：

- 真实临时 SQLite 查询和结果映射。
- 最大行数和截断标记。
- UPDATE 二次确认。
- SELECT、修改语句、DDL、多语句和禁止语句风险分类样例。

## 8:00-9:00 app-image

运行：

```powershell
.\packaging\package-stage1.ps1
```

展示 `target\installer\SQLTeacherStage1`，说明该产物是第一轮 app-image，不是最终安装器。

## 9:00-10:00 下一步和限制

- 7 月 15 日接入 JavaFX 到真实 `SqlExecutionService`。
- 7 月 16-19 日实现事件记录、完整风险提示和 AI 失败降级。
- 指向 `docs/acceptance/first-round-known-limitations.md`，避免把占位或 Mock 功能描述为已完成。
