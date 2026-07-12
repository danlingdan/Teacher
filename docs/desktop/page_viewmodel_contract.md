# 桌面页面 ViewModel 契约（新版后端接口对齐版）

> 适用范围：`com.sqlteacher.desktop` 桌面端。本文件记录各页面 UI 渲染所需的全部 ViewModel 字段，
> 并与重新封装后的后端 Application Service 接口 / DTO 一一对应。
>
> 约束回顾：
> - ViewModel 生产代码位于 `src/main/java/com/sqlteacher/desktop/viewmodel/`。
> - ViewModel 统一使用 Java 21 `record` + 紧凑构造器，集合字段默认 `List.of()`，禁止 `null`。
> - ViewModel 不直接引入 infrastructure 枚举 ；状态统一转为 `UiStatusLevel`。
> - `connectionId` 统一使用全局常量 `DesktopConnections.DEMO`（固定值 `"demo"`）。
> - ViewModel 只保留 UI 展示必需字段，不持有后端原始 DTO / 数据库实体。
> - Mock 契约单元测试位于 `src/test/java/com/sqlteacher/desktop/mock/`；其中离线程序运行期需要注入的
>   `SqlExecutionMockService` 与共享枚举 `MockScenario` 已下沉到 `src/main/java/com/sqlteacher/desktop/mock/`
>   （同包名、非模块化构建下 split package 合法），其余 Mock 与契约测试仍在 test 源集。

## 0. 公共约定

### 0.1 连接标识常量

| 常量 | 值 | 位置 |
|---|---|---|
| `DesktopConnections.DEMO` | `"demo"` | `desktop/viewmodel/DesktopConnections.java` |

生产 ViewModel 工厂方法与全部 Mock 服务都复用该常量，禁止分散硬编码字面量 `"demo"`。

### 0.2 UI 状态中转枚举 `UiStatusLevel`

用于隔离 infrastructure 枚举 `com.sqlteacher.infrastructure.environment.VerificationStatus`。
ViewModel 不 import 该 infrastructure 枚举，转换通过传入枚举名字符串完成。

| `UiStatusLevel` | 显示标签 | 来源映射 |
|---|---|---|
| `SUCCESS` | 正常 | `VerificationStatus.PASS`；或 `success == true` |
| `WARNING` | 警告 | `VerificationStatus.WARNING` |
| `ERROR` | 异常 | `VerificationStatus.FAIL`；或 `success == false` |
| `UNKNOWN` | 未知 | 空值 / 未识别的状态名 |

转换入口：`UiStatusLevel.fromStatusName(String)`、`UiStatusLevel.fromSuccessFlag(boolean)`。

---

## 1. 首页 / 环境状态页

- **页面名称**：首页 / 环境状态页（Home / Environment Status）
- **ViewModel 类名**：`HomeStatusViewModel`（组合 `DatabaseStatusViewModel` + `AiStatusViewModel`）
- **绑定新版后端接口**：
  - `AppConfigurationService.current()` → `SqlTeacherProperties`
  - `DatabaseInitializationService.initialize()` → `DatabaseInitializationResult`
  - `AiStatusService.checkStatus()` → `AiStatus`

### 1.1 `HomeStatusViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `appName` | `String` | 应用名称，用于窗口标题 | `SqlTeacherProperties.appName()` |
| `connectionId` | `String` | 当前演示连接标识 | 常量 `DesktopConnections.DEMO`（`"demo"`） |
| `dataDirectory` | `String` | 运行期数据目录展示 | `SqlTeacherProperties.dataDirectory()`（`Path` → `String`） |
| `database` | `DatabaseStatusViewModel` | 数据库初始化状态卡片 | `DatabaseInitializationResult`（见 1.2） |
| `ai` | `AiStatusViewModel` | AI / Ollama 状态卡片 | `AiStatus`（见 3.2） |

> 说明：`appName`、`dataDirectory` 由调用方从 `SqlTeacherProperties` 抽取为原始字符串后传入，
> 使 `HomeStatusViewModel` 不 import infrastructure 配置类型。

### 1.2 `DatabaseStatusViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `appDatabasePath` | `String` | 应用库 `app.db` 路径 | `DatabaseInitializationResult.appDatabasePath()`（`Path` → `String`） |
| `demoDatabasePath` | `String` | 演示库 `demo.db` 路径 | `DatabaseInitializationResult.demoDatabasePath()`（`Path` → `String`） |
| `appDatabaseCreated` | `boolean` | 本次是否新建应用库 | `DatabaseInitializationResult.appDatabaseCreated()` |
| `demoDatabaseCreated` | `boolean` | 本次是否新建演示库 | `DatabaseInitializationResult.demoDatabaseCreated()` |
| `statusLevel` | `UiStatusLevel` | 初始化结果状态徽标 | 有结果即 `SUCCESS`（派生） |
| `summary` | `String` | 一句话中文摘要 | 由 `appDatabaseCreated` / `demoDatabaseCreated` 派生 |

---

## 2. SQL 练习页

- **页面名称**：SQL 练习页（SQL Practice，对应交付计划 P0-05）
- **ViewModel 类名**：`SqlExecutionViewModel`（含行模型 `SqlResultRowViewModel`）
- **绑定新版后端接口**：`SqlExecutionService.execute(SqlExecutionRequest)` → `SqlExecutionResult`

### 2.1 `SqlExecutionViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `connectionId` | `String` | 执行所用连接标识 | `SqlExecutionRequest.connectionId()`（默认 `DesktopConnections.DEMO`） |
| `executedSql` | `String` | 实际执行的 SQL 文本 | `SqlExecutionRequest.sql()` |
| `success` | `boolean` | 是否执行成功 | `SqlExecutionResult.success()` |
| `statusLevel` | `UiStatusLevel` | 结果区状态徽标 | `UiStatusLevel.fromSuccessFlag(success)` |
| `columns` | `List<String>` | 结果表列头 | `SqlExecutionResult.columns()` |
| `rows` | `List<SqlResultRowViewModel>` | 结果表数据行 | `SqlExecutionResult.rows()` 展平映射（见 2.2） |
| `rowCount` | `int` | 返回行数 | `rows.size()`（派生） |
| `affectedRows` | `int` | 非查询语句影响行数 | `SqlExecutionResult.affectedRows()` |
| `truncated` | `boolean` | 结果集是否被截断（用于 UI 提示"仅显示前 N 行"） | `SqlExecutionResult.truncated()` |
| `executionMillis` | `long` | 执行耗时（毫秒），用于展示 | `SqlExecutionResult.duration()`（`Duration` → `toMillis()`） |
| `message` | `String` | 成功提示 / 可读错误信息 | `SqlExecutionResult.message()` |

> 裁剪说明：后端 `rows` 为 `List<Map<String, Object>>`，ViewModel 不持有该原始 `Map`，
> 而是按 `columns` 顺序展平为字符串单元格，避免向 UI 泄漏底层结构与类型。
> 后端 `duration` 为 `java.time.Duration`，ViewModel 转为 `executionMillis`（`long`）以隔离时间类型。

### 2.2 `SqlResultRowViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `cells` | `List<String>` | 与 `columns` 顺序对齐的单元格显示文本 | `SqlExecutionResult.rows()` 中每个 `Map` 按列取值，`null` → `"NULL"` |

---

## 3. AI 助手页

- **页面名称**：AI 助手页（AI Assistant / NL2SQL 草案，对应交付计划 P0-07）
- **ViewModel 类名**：`AiAssistantViewModel`（含 `AiStatusViewModel`）
- **绑定新版后端接口**：
  - `Nl2SqlService.generate(Nl2SqlRequest)` → `Nl2SqlPlan`
  - `AiStatusService.checkStatus()` → `AiStatus`

### 3.1 `AiAssistantViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `connectionId` | `String` | 生成草案所用连接标识 | `Nl2SqlRequest.connectionId()`（默认 `DesktopConnections.DEMO`） |
| `naturalLanguage` | `String` | 用户输入的自然语言问题 | `Nl2SqlRequest.naturalLanguage()` |
| `sqlDraft` | `String` | 模型生成的可预览 SQL 草案 | `Nl2SqlPlan.sqlDraft()` |
| `intent` | `String` | 模型识别出的查询意图 | `Nl2SqlPlan.intent()` |
| `explanation` | `String` | 模型对草案的说明 | `Nl2SqlPlan.explanation()` |
| `model` | `String` | 生成所用模型标识 | `Nl2SqlPlan.model()` |
| `promptVersion` | `String` | 提示词模板版本 | `Nl2SqlPlan.promptVersion()` |
| `draftAvailable` | `boolean` | 是否存在可展示草案 | 由 `sqlDraft` 是否非空派生 |
| `aiStatus` | `AiStatusViewModel` | 模型可用性状态（用于禁用/提示） | `AiStatus`（见 3.2） |

> 后端结构说明：重新封装后的 `Nl2SqlPlan` 依次包含 `sqlDraft`、`intent`、`explanation`、`model`、
> `promptVersion` 五个字段。ViewModel 完整对齐该结构，并以 `sqlDraft` 非空作为 `draftAvailable`
> 的判定依据（即"有可运行草案才展示预览区"）。`model` / `promptVersion` 作为草案来源溯源信息展示。
> 安全提示：`sqlDraft` 仅为草案预览文本，必须先经后端校验与风险分析、并在需要时经用户确认后才能执行。

### 3.2 `AiStatusViewModel`

| 字段 | 类型 | 业务含义 | 后端来源字段 |
|---|---|---|---|
| `statusLevel` | `UiStatusLevel` | AI 服务状态徽标 | `UiStatusLevel.fromStatusName(AiStatus.status().name())` |
| `provider` | `String` | 模型提供方（如 `ollama`） | `AiStatus.provider()` |
| `endpoint` | `String` | 服务地址 | `AiStatus.endpoint()` |
| `modelCount` | `int` | 已安装模型数量 | `AiStatus.modelCount()` |
| `available` | `boolean` | 服务是否可用 | `AiStatus.available()` |
| `message` | `String` | 状态说明文本 | `AiStatus.message()` |

---

## 4. Mock 模拟服务与契约测试

### 4.1 Mock 服务（位于 `src/test/java/com/sqlteacher/desktop/mock/`）

| Mock 服务 | 实现后端接口 | 三种场景（`MockScenario`） |
|---|---|---|
| `SqlExecutionMockService` | `SqlExecutionService` | `NORMAL` 3 行数据 / `EMPTY` 0 行 / `ERROR` `success=false` 失败结果 |
| `Nl2SqlMockService` | `Nl2SqlService` | `NORMAL` 有草案 / `EMPTY` 空草案 / `ERROR` 抛 `MockBackendException` |
| `AiStatusMockService` | `AiStatusService` | `NORMAL` PASS+2 模型 / `EMPTY` PASS+0 模型 / `ERROR` WARNING 不可用 |
| `DatabaseInitializationMockService` | `DatabaseInitializationService` | `NORMAL` 新建 / `EMPTY` 已存在 / `ERROR` 抛 `MockBackendException` |
| `AppConfigurationMockService` | `AppConfigurationService` | `NORMAL` 完整配置 / `EMPTY` 空 appName / `ERROR` 抛 `MockBackendException` |

- `MockScenario`：`NORMAL`（正常完整数据）、`EMPTY`（结构合法的空数据）、`ERROR`（接口异常数据）。
- `ERROR` 语义：能被 DTO 内联表达失败的接口返回失败 DTO（`SqlExecutionResult.success=false`、
  非 PASS 的 `AiStatus`）；无法内联表达失败的接口抛 `MockBackendException`。
- 所有 Mock 内部统一使用 `DesktopConnections.DEMO` 作为 `connectionId`。

### 4.2 异步调度工具 `AsyncMockInvoker`

- 在后台线程执行后端调用，避免阻塞 JavaFX 主线程。
- 通过通用 `java.util.concurrent.Executor` 表示 UI 线程（JavaFX 侧可传入基于
  `Platform.runLater` 的 executor），工具类本身不 import JavaFX，可独立单测。
- 提供 `invokeAsync(Supplier)` 返回 `CompletableFuture`，以及
  `invoke(Supplier, Executor, onSuccess, onError)` 回调式接口，异常经 `CompletionException` 解包后回调。

### 4.3 契约单元测试 `MockServiceContractTest`

- 校验 Mock 返回数据结构与新版后端接口 DTO 完全匹配。
- 校验 DTO → ViewModel 字段映射与类型转换（含 `UiStatusLevel` 映射、`Path` → `String`、
  `Map` 行展平）无异常。
- 校验 `AsyncMockInvoker` 正常与异常路径。
- 不依赖 JavaFX 运行环境，可独立执行。
