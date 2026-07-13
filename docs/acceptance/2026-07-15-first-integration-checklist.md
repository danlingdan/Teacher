# 2026-07-15 第一次联调检查清单

## 目标

将已独立完成的真实 SQLite SQL 服务接入 JavaFX SQL 练习页，形成第一条真实 UI 到数据库链路。

## 联调前基线

- [x] Application Service 和 DTO 已合并到 `develop`。
- [x] SQLite 初始化、SQL 执行、风险分析 Bean 已合并。
- [x] JavaFX SQL 练习页能够使用 Mock 服务运行。
- [x] `mvn test` 可运行。
- [x] E 第一轮测试、打包和验收材料 PR 已合并。

## 联调步骤

1. A/D 在 JavaFX 生命周期中创建并关闭 Spring Context。
2. 从 Spring 获取 `DatabaseInitializationService` 和真实 `SqlExecutionService`。
3. 在非 JavaFX Application Thread 上完成数据库初始化。
4. 将真实 `SqlExecutionService` 注入 `MainWindowController`。
5. 保留 `SqlPracticeController` 现有后台执行和 loading/success/failure 状态。
6. E 运行下列场景并记录结果。

## 必测场景

| 场景 | 预期 | 状态 |
|---|---|---|
| `SELECT * FROM student` | 展示真实列和数据行 | 通过：打包版 UI 展示 Alice、Bob |
| 查询无结果 | 展示空状态 | 通过：服务级联调测试 |
| SQL 语法错误 | 展示学生可理解的错误 | 通过：错误进入 UI 失败状态 |
| 两条 SQL | 默认拦截 | 通过：`SQL_BLOCKED` |
| `DROP TABLE student` | 禁止执行 | 通过：打包版 UI 显示 schema 风险原因 |
| 未确认的 `UPDATE` | 要求确认，不修改数据 | 通过：`SQL_CONFIRMATION_REQUIRED`，数据未改变 |
| 超过最大行数 | 限制返回并标记截断 | 通过：返回上限内数据并显示截断提示 |

## 退出条件

- `mvn test` 通过。
- UI 能执行真实 SQLite `SELECT` 并展示结果。
- 禁止 SQL 无法通过 UI 执行。
- 联调问题有负责人、优先级和计划完成日期。
- 不在本窗口引入 AI UI、事件记录或其他第二轮需求。

## 执行结果

- 2026-07-13 提前执行第一次联调，结果通过。
- `mvn test`：70 项测试通过，0 失败、0 错误、0 跳过。
- app-image 已生成并实际启动，真实查询和危险 SQL 拦截均通过 UI 验证。
- 详细记录见 `docs/stage1/2026-07-13-first-integration-report.md`。
