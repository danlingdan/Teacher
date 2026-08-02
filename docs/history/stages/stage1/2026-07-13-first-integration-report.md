# 第一次联调报告

执行日期：2026-07-13。

## 联调范围

本次只串联第一次联调窗口要求的桌面 SQL 练习页与真实 SQLite 服务，不引入 AI UI、事件记录或第二轮功能。

完成内容：

- JavaFX `init()` 创建 Spring Context 并在非 JavaFX Application Thread 初始化 SQLite。
- 从 Spring 获取真实 `SqlExecutionService` 并构造注入主窗口和 SQL 练习页。
- JavaFX `stop()` 关闭 Spring Context。
- 示例 SQL 与真实 `student(id, name, score)` schema 对齐。
- 查询结果显示行数、耗时和截断提示。
- 页面增加纵向滚动，结果数据行在 1180×720 窗口中可见。
- app-image 将 JavaFX 模块与普通 classpath 依赖隔离，并显式加载 SQLite JDBC 驱动。

## 自动化验证

命令：

```powershell
mvn test
```

结果：70 项测试通过，0 失败、0 错误、0 跳过。

新增 `FirstIntegrationFlowTest`，使用临时 SQLite 数据库和 Spring 数据库配置验证：

- 真实 `SELECT` 返回 Alice、Bob。
- 空结果正常返回。
- SQL 语法错误进入失败链路。
- 多语句和 `DROP TABLE` 被阻止。
- 未确认 `UPDATE` 不修改数据。
- 查询超过上限时标记截断。

## 桌面与打包验证

执行：

```powershell
.\packaging\package-stage1.ps1
```

生成 `target/installer/SQLTeacherStage1/SQLTeacherStage1.exe`。实际启动打包版并完成以下 UI 操作：

1. 执行 `SELECT * FROM student ORDER BY id;`。
2. 页面展示 `id/name/score` 和 Alice、Bob 两行真实数据。
3. 执行 `DROP TABLE student;`。
4. 页面拒绝执行并显示 `This statement modifies database schema.`。
5. 关闭应用后无残留 SQLTeacher 进程。

## 联调中修复的问题

| 优先级 | 问题 | 处理结果 |
|---|---|---|
| P0 | 桌面仍注入 Mock SQL 服务 | 已接入 Spring 中的真实服务 |
| P0 | app-image 中 SQLite 驱动未被 classpath 正确发现 | JavaFX 独立放入 module-path，并显式加载 SQLite JDBC |
| P0 | 结果卡片高度不足，真实数据行不可见 | 页面增加滚动并设置结果区最小高度 |
| P1 | 示例 SQL 使用不存在的 `grade/class` 字段 | 全部改为真实 `score` 字段 |
| P1 | 截断结果没有 UI 提示 | 增加结果摘要和截断提示 |

## 剩余限制

- 错误和风险原因仍以基础设施英文信息为主，后续应通过应用异常映射统一为学生友好中文。
- 风险确认弹窗、表结构浏览、事件记录和 AI UI 按后续计划实现。
