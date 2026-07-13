# 2026-07-10 P0 接口冻结记录

## 冻结范围

本记录冻结初步演示版的 Application Service、公共 DTO 和模块交换字段。冻结基线以本文件所在提交为准。实现类、FXML、CSS、Prompt 文本、数据库适配器内部结构和测试夹具不属于公共接口。

`AiStatus` 和 `AppConfigurationService` 在冻结基线中曾引用 infrastructure 类型。A 的第一轮实现已将它们迁移为 application 自有的 `AiAvailability`、`SqlTeacherConfiguration`、`DatabaseConfiguration` 和 `AiConfiguration`，消除了 application 对 infrastructure 的反向依赖。

冻结接口：

- 配置与状态：`AppConfigurationService`、`AiStatusService`
- 初始化与元数据：`DatabaseInitializationService`、`DatabaseMetadataService`
- SQL 安全与执行：`SqlRiskAnalysisService`、`SqlExecutionService`
- AI 草案：`Nl2SqlService`
- 事件记录：`LearningEventRecorder`

A 第二轮新增 `LearningEventService` 作为调用方使用的语义化入口；既有
`LearningEventRecorder` 保持为存储端口。详细事件字段、隐私边界和迁移方式见
`docs/stage1/2026-07-13-a-second-round-contracts.md`。

## 模块契约确认

### B：数据库与 SQL 安全

- 输入：连接标识、单条 SQL、最大行数、超时、用户是否已确认风险。
- 风险输出：等级、是否允许执行、是否需确认、是否多语句、语句类型、原因列表。
- 执行输出：成功状态、列名、行数据、影响行数、是否截断、学生可读消息、耗时。
- 元数据输出：表名及列名、数据库类型、可空性、主键标记。
- 约束：多语句默认禁止；`FORBIDDEN` 不得执行；查询结果遵守 `maxRows`。

### C：AI 与 NL2SQL

- 输入：自然语言和连接标识。
- Provider 结构化 JSON 字段固定为：

```json
{
  "sqlDraft": "SELECT ...",
  "intent": "QUERY",
  "explanation": "..."
}
```

- Application 输出另附 `model` 和 `promptVersion`。
- 约束：SQL 只作为草案返回，C 模块不得调用执行服务；解析失败或 Ollama 不可用时必须可读地失败。

### D：JavaFX

- SQL 练习页直接消费 `SqlExecutionResult` 和 `SqlRiskAnalysis`。
- 结果表列由 `columns` 决定，单元格按每行 Map 的同名键读取。
- 表结构树消费 `DatabaseTable` / `DatabaseColumn`。
- AI 页面展示 `sqlDraft`、`explanation`、`model`，仅提供预览或复制到练习页。
- 加载、成功和失败状态属于 UI 内部 ViewModel，不加入公共 Application DTO。

### E：测试、打包与验收

- 服务契约测试：空集合不返回 `null`，集合 DTO 创建后不可被外部修改。
- SQL 安全：覆盖普通 `SELECT`、高风险写操作、`DROP DATABASE`、`GRANT`、`REVOKE` 和多语句。
- AI：覆盖合法 JSON、非法 JSON、超时、无模型和 Ollama 不可用。
- 事件：覆盖执行成功、执行失败、风险拦截、AI 生成成功与失败。
- 通用验收入口：`mvn test`、Stage 1 CLI 验证和 app-image 验证。

## 变更控制

- 2026-07-10 后默认冻结；固定调整窗口为 7 月 15、20、25 日。
- 变更申请必须记录影响成员、文件、迁移方式和测试方式。
- 非窗口期只接受编译修复、字段命名错误或 P0 严重阻塞。
- 公共接口变更由 A 审核；SQL 安全变更还需 B 审核。

## A 第一轮迁移记录

- 日期：2026-07-10。
- 影响成员：B、C、D、E。
- 接口变化：`AppConfigurationService.current()` 返回值由 infrastructure 配置类型迁移为 `SqlTeacherConfiguration`；`AiStatus.status()` 改用 `AiAvailability`。
- 迁移方式：调用方只需替换类型导入；字段访问器保持 `appName()`、`database()`、`ai()`、`status()` 等原有命名。
- UI mock：D 可单独使用 `MockApplicationServiceConfig` 获取 SQL 执行、风险、元数据、NL2SQL、AI 状态及异常映射服务。
- 验证方式：`mvn test`，并运行 `StageOneVerificationApp` 检查真实配置装配。

## 分支基线

所有成员分支从同一 `develop` 冻结提交创建：

| 成员 | 分支 | 主范围 |
|---|---|---|
| A | `feature/a-application-contracts` | application、domain、架构与编排 |
| B | `feature/b-database-sql-safety` | database、SQL 执行与风险 |
| C | `feature/c-ai-nl2sql` | AI provider、Prompt、解析与回归样例 |
| D | `feature/d-javafx-desktop` | desktop、FXML、CSS |
| E | `feature/e-test-packaging-docs` | tests、docs、packaging |

成员只向自己的功能分支提交，通过 Pull Request 合入 `develop`；`master` 仅接收稳定演示版本。
