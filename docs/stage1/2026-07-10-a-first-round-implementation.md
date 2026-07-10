# A 第一轮独立实现记录

## 1. 完成范围

本记录对应 `2026-07-11 至 2026-07-14：第一轮独立实现` 中成员 A 的任务：应用服务骨架、配置、日志和统一异常映射。

已完成：

- 保留并整理 SQL 执行、风险分析、元数据、NL2SQL、AI 状态等 Application Service 契约。
- 将配置与 AI 可用状态 DTO 迁入 application 层，application 不再依赖 infrastructure 类型。
- 增加 `ApplicationExceptionMapper`、`ApplicationError` 和默认映射实现。
- 已知业务异常保留错误码和安全提示；非法请求和未知异常统一转换为可展示错误，堆栈只进入日志。
- 真实 Spring 配置提供统一异常映射 Bean。
- 增加独立的 UI mock 配置，可提供 SQL 执行、风险分析、元数据、NL2SQL 和 AI 状态服务。
- 保持现有 Logback 控制台及滚动文件日志配置，异常映射使用 SLF4J 记录异常对象。

## 2. UI mock 使用方式

D 的页面开发可以创建独立上下文，不需要 SQLite 或 Ollama：

```java
try (var context = new AnnotationConfigApplicationContext(MockApplicationServiceConfig.class)) {
    SqlExecutionService executionService = context.getBean(SqlExecutionService.class);
    SqlRiskAnalysisService riskService = context.getBean(SqlRiskAnalysisService.class);
    ApplicationExceptionMapper exceptionMapper = context.getBean(ApplicationExceptionMapper.class);
}
```

mock 的边界：

- 只用于 UI 开发和测试，不进入真实运行配置。
- SQL mock 只允许单条 `SELECT`，其他语句和多语句一律返回禁止结果。
- NL2SQL mock 只返回 SQL 草案，不调用执行服务。
- 真实 SQL 分类、执行、行数限制仍由 B 的模块实现。
- 真实 Ollama Provider、结构化输出解析仍由 C 的模块实现。

## 3. 异常映射契约

UI 捕获服务异常后调用 `ApplicationExceptionMapper.map(Throwable)`，只展示返回的 `userMessage`，不得展示原始堆栈。

| 异常来源 | 错误类型 | 展示策略 |
|---|---|---|
| `SqlTeacherException` | `APPLICATION` | 保留业务错误码和业务安全提示 |
| `IllegalArgumentException` | `VALIDATION` | 返回统一输入检查提示 |
| 其他异常 | `UNEXPECTED` | 隐藏内部细节，提示重试或联系教师 |

## 4. 验证记录

执行命令：

```powershell
mvn test
```

结果：通过。测试数 18，失败 0，错误 0，跳过 0；编译目标为 Java 21。

阶段 1 CLI 验证通过 Spring、SQLite 应用库和演示库检查；本机 Ollama 当前不可连接，按设计返回 `WARNING`，应用未崩溃。

覆盖内容：

- application DTO 集合不可变性。
- application 自有配置 DTO 加载。
- 已知和未知异常的统一映射。
- UI mock 的查询结果、危险语句拦截和 NL2SQL 草案。
- mock Spring 配置中的服务可被调用。
- 真实 Spring 配置仍可装配配置、SQLite 初始化、AI 状态和异常映射服务。

## 5. 已知限制

- 本轮不实现真实 SQL 执行和风险分类，它们属于 B。
- 本轮不实现真实 NL2SQL 调用和 JSON 解析，它们属于 C。
- 事件记录服务和更细化的用户错误信息属于 A 的第二轮任务，本轮只保留既有契约。
