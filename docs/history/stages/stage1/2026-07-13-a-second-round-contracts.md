# A 第二轮事件与错误契约记录

## 1. 完成范围

本记录对应第二轮独立实现中成员 A 的任务：事件记录服务、用户错误信息和接口文档。

- 新增 `LearningEventService` 高层应用服务，分别记录 SQL 执行、SQL 风险拦截和 AI 生成结果。
- `DefaultLearningEventService` 只向 `LearningEventRecorder` 端口提交标准事件；调用方不依赖 SQLite 表、文件格式或序列化方式。
- 事件属性不接受原始 SQL、自然语言问题、模型输出或异常详情，避免将教学数据和潜在敏感内容写入审计记录。
- `DefaultApplicationExceptionMapper` 按现有错误码返回安全、可理解的中文信息；已知基础设施错误不再把 JDBC 或配置异常原文展示给学生。
- 补充事件校验、属性映射、隐私边界和错误信息回退测试。

本轮不实现 SQLite 事件仓储，也不修改 SQL、AI 或 JavaFX 调用顺序。SQLite 适配和业务链路接入分别属于 B 的第二轮任务与第二次联调窗口。

## 2. 事件服务契约

调用方只依赖：

```text
LearningEventService
        -> DefaultLearningEventService
        -> LearningEventRecorder
        -> 由 B 提供的存储适配器
```

高层方法：

| 方法 | 用途 | 必填的非敏感信息 |
|---|---|---|
| `recordSqlExecution` | SQL 成功或失败 | 连接标识、语句类型、耗时、结果数量、可选错误码 |
| `recordSqlRiskBlocked` | SQL 被安全规则拦截 | 连接标识、语句类型、风险等级、是否多语句 |
| `recordAiGeneration` | AI 生成成功或失败 | 连接标识、模型、Prompt 版本、可选错误码 |

适配器最终接收的 `LearningEvent` 包含事件类型、UTC 时间、连接标识、成功状态和不可变属性。存储失败不得在应用服务中静默吞掉；具体适配器应转换为有错误码的应用异常，再交给统一异常映射处理。

禁止加入事件属性的内容：

- 原始 SQL 和 SQL 参数值。
- 自然语言问题、Prompt 全文或模型完整输出。
- JDBC URL、数据库密码、令牌和本地绝对路径。
- 原始异常消息或堆栈。

## 3. 用户错误信息契约

`ApplicationExceptionMapper` 的输出仍为 `ApplicationError(code, type, userMessage, retryable)`。UI 只能展示 `userMessage`，日志可通过异常映射器保留内部异常对象。

当前已固定安全提示的错误码：

- `CONFIG_NOT_FOUND`、`CONFIG_LOAD_FAILED`、`CONFIG_INVALID`
- `SQLITE_INIT_FAILED`、`DATABASE_METADATA_FAILED`
- `SQL_BLOCKED`、`MOCK_SQL_BLOCKED`、`SQL_CONFIRMATION_REQUIRED`
- `SQL_EXECUTION_FAILED`

未知业务错误保留错误码，但使用统一安全提示，不直接展示异常原文。非法参数映射为 `INVALID_REQUEST`；未知异常映射为可重试的 `UNEXPECTED_ERROR`。

## 4. 接口影响与迁移

- 影响成员：B、C、D、E。
- 兼容性：既有 `LearningEvent`、`LearningEventRecorder` 和 `ApplicationExceptionMapper` 方法签名未变；新增高层服务，不要求现有调用方立即迁移。
- B：实现 `LearningEventRecorder` 存储适配器，不让 application 层引用 JDBC。
- C：AI 生成结束后只传模型、Prompt 版本和错误码，不传问题或 SQL 草案。
- D：继续只展示 `ApplicationError.userMessage()`。
- E：可使用内存 recorder 测试四类 P0 事件，不需要真实 SQLite。

## 5. 验证

执行：

```powershell
mvn test
```

结果：通过。测试数 76，失败 0，错误 0，跳过 0；编译目标为 Java 21。
