# 开发规范

## 1. Java 基础规则

- 使用 `pom.xml` 中配置的 Java 版本。
- 文件编码统一为 UTF-8。
- 缩进使用 4 个空格。
- 单行代码建议不超过 120 字符。
- 类名使用大驼峰。
- 方法名和变量名使用小驼峰。
- 常量使用全大写和下划线。
- 包名全部小写。
- 禁止使用拼音命名。
- 禁止提交无意义注释和大段废弃代码。
- 禁止在业务代码中直接 `System.out.println`。
- 日志使用 SLF4J 或项目统一日志方案。
- 所有外部输入必须校验。

如果文档中 Java 版本与 `pom.xml` 不一致，以 `pom.xml` 为准；需要统一时必须同时修改构建配置和文档。

## 2. 类命名规范

| 类型 | 后缀示例 |
|---|---|
| 应用服务 | `ExecutionService` |
| 接口 | `DatabaseAdapter` |
| 实现类 | `SqliteDatabaseAdapter` |
| 配置类 | `AiModelProperties` |
| DTO | `SqlTaskPlanDto` |
| 请求对象 | `SqlExecutionRequest` |
| 响应对象 | `SqlExecutionResult` |
| 异常 | `SqlExecutionException` |
| 控制器 | `MainWindowController` |
| 测试类 | `SqlRiskAnalyzerTest` |

## 3. 方法规范

- 一个方法只做一件明确的事。
- 方法参数超过 4 个时优先使用请求对象。
- 对可能失败的外部调用必须处理异常。
- 不允许吞掉异常。
- 业务异常使用自定义异常。
- 禁止返回 `null` 表示集合结果，空集合使用 `List.of()` 或 `Collections.emptyList()`。
- 对外暴露的 DTO 尽量使用 `record`。

示例：

```java
public record SqlExecutionRequest(
    String connectionId,
    String sql,
    int maxRows,
    Duration timeout
) {
}
```

## 4. 异常处理规范

异常分为三类：

| 类型 | 示例 | 处理方式 |
|---|---|---|
| 用户输入错误 | SQL 语法错误、连接配置错误 | 转换为用户可理解提示 |
| 外部依赖错误 | MySQL 不可达、Ollama 超时 | 提供重试或降级 |
| 系统错误 | 数据损坏、未知异常 | 记录日志并提示联系开发者 |

要求：

- 不直接把堆栈展示给用户。
- 日志中保留异常对象。
- UI 提示中说明用户下一步能做什么。
- 不用空 `catch`。

## 5. 日志规范

日志级别：

| 级别 | 使用场景 |
|---|---|
| `debug` | 开发调试信息 |
| `info` | 关键业务流程 |
| `warn` | 可恢复异常或风险行为 |
| `error` | 不可恢复异常或系统错误 |

要求：

- 日志中不得记录数据库密码。
- 日志中不得记录完整大结果集。
- SQL 文本日志需要根据配置控制。
- 用户敏感信息必须脱敏。
- 异常日志必须包含异常对象。

示例：

```java
log.warn("SQL execution blocked, reason={}, connectionId={}", reason, connectionId);
log.error("Failed to connect database, type={}", databaseType, ex);
```

## 6. JavaFX 前端规范

界面开发要求：

- 使用 FXML + CSS。
- Controller 不直接写复杂业务逻辑。
- Controller 调用 Application Service。
- 长耗时任务必须放入后台线程。
- AI 调用、数据库连接、SQL 执行不能阻塞 UI 线程。
- 所有按钮必须有加载、成功、失败状态。
- 错误提示要能让学生理解。
- 高风险操作必须使用确认弹窗。
- 表格结果必须支持横向滚动。
- 低分辨率屏幕下主要按钮不能被遮挡。

页面建议：

| 页面 | 主要功能 |
|---|---|
| 首页 | 快速进入内置库、连接外部库、查看模型状态 |
| 数据库连接页 | SQLite、MySQL 连接配置和测试 |
| SQL 练习页 | 编辑 SQL、执行、查看结果和错误 |
| AI 助手页 | 自然语言输入、SQL 预览、解释 |
| 知识库页 | 课程文档检索和常见错误说明 |
| 学情看板页 | 成功率、尝试次数、常见错误、导出 |
| 设置页 | 模型、数据库、日志、数据清理 |

## 7. Git 分支规范

推荐分支：

```text
main       稳定可演示版本
develop    日常集成版本
feature/*  功能开发分支
fix/*      缺陷修复分支
release/*  阶段发布分支
```

分支命名：

```text
feature/sqlite-adapter
feature/javafx-main-window
feature/nl2sql-provider
fix/mysql-timeout
release/v0.1-demo
```

合并规则：

- 所有人从 `develop` 拉取新分支。
- 功能完成后提交 Pull Request 或 Merge Request。
- 至少 1 人代码评审后合并。
- 涉及公共接口、SQL 安全、打包脚本的改动必须由 A 或对应负责人审核。
- `main` 只合并阶段稳定版本。
- 禁止直接向 `main` 推送未审核代码。

## 8. 提交信息规范

提交信息使用以下格式：

```text
类型(模块): 简短说明
```

常用类型：

| 类型 | 说明 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 文档 |
| `style` | 代码格式，不改变逻辑 |
| `refactor` | 重构 |
| `test` | 测试 |
| `build` | 构建或依赖 |
| `chore` | 杂项维护 |

示例：

```text
feat(database): add sqlite adapter
feat(ai): support ollama structured output
fix(sql): block multi statement execution
docs(plan): update week 3 task list
test(mysql): add connection timeout tests
build(package): add jpackage script
```

## 9. 文档规范

- 公共接口变更必须更新文档。
- 文档描述必须与当前实现一致。
- 未实现功能要标注“计划”或“暂不实现”。
- 不在文档中写真实密码、token、个人隐私数据。
- 示例路径和命令要能被团队成员复现。
